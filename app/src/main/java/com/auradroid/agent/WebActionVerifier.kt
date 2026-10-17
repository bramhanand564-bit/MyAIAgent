package com.auradroid.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.random.Random

data class WebDomSnapshot(
    val url: String,
    val title: String,
    val heuristicState: String,
    val popupObstacleText: String,
    val renderingPercent: Int,
    val detectedMp4Urls: List<String>,
    val interactiveSummary: String,
    val stateSignature: String
)

data class ClickExecutionReport(
    val success: Boolean,
    val senseUsed: String,
    val targetX: Float,
    val targetY: Float,
    val needsProModelEscalation: Boolean,
    val details: String
)

data class ActionVerifyOutcome(
    val stateChanged: Boolean,
    val previousState: String,
    val newState: String,
    val summaryLog: String
)

object WebActionVerifier {

    const val VIRTUAL_WIDTH = 540f
    const val VIRTUAL_HEIGHT = 960f
    const val FLOW_PLAY_CARD_X = 92f
    const val FLOW_PLAY_CARD_Y = 286f

    suspend fun evaluateJsSuspend(webView: WebView, script: String): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                try {
                    webView.evaluateJavascript(script) { rawResult ->
                        if (cont.isActive) {
                            cont.resume(unquoteJsString(rawResult ?: ""))
                        }
                    }
                } catch (e: Exception) {
                    if (cont.isActive) {
                        cont.resume("")
                    }
                }
            }
        }

    private fun unquoteJsString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "null" or trimmed.isEmpty()) return ""
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
            return try {
                JSONArray("[$trimmed]").optString(0, "")
            } catch (_: Exception) {
                trimmed.substring(1, trimmed.length - 1)
            }
        }
        return trimmed
    }

    suspend fun captureWebViewBitmap(webView: WebView): Bitmap? = withContext(Dispatchers.Main) {
        try {
            val w = webView.width.coerceAtLeast(540)
            val h = webView.height.coerceAtLeast(960)
            val bmp = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val scaleX = 540f / w.toFloat()
            val scaleY = 960f / h.toFloat()
            canvas.scale(scaleX, scaleY)
            webView.draw(canvas)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    suspend fun inspectLiveDomState(webView: WebView): WebDomSnapshot {
        val js = """
(function() {
  try {
    var vw = window.innerWidth > 0 ? window.innerWidth : 540;
    var vh = window.innerHeight > 0 ? window.innerHeight : 960;
    var to540X = function(x) { return Math.round((x / vw) * 540); };
    var to960Y = function(y) { return Math.round((y / vh) * 960); };

    var popupKeywords = ['got it', 'accept all', 'i agree', 'dismiss', 'allow cookies', 'continue'];
    var foundPopup = '';
    var buttons = document.querySelectorAll('button, [role="button"], a, input[type="button"], input[type="submit"]');
    var items = [];

    for (var i = 0; i < buttons.length; i++) {
      var el = buttons[i];
      var rect = el.getBoundingClientRect();
      if (rect.width < 8) continue;
      if (rect.height < 8) continue;
      var txt = (el.innerText ? el.innerText : (el.getAttribute('aria-label') ? el.getAttribute('aria-label') : '')).trim();
      if (!txt) continue;
      var cleanTxt = txt.replace(/\s+/g, ' ').substring(0, 60);
      var cx = to540X(rect.left + rect.width / 2);
      var cy = to960Y(rect.top + rect.height / 2);
      items.push('BTN[' + cleanTxt + ']@(' + cx + ',' + cy + ')');
      var low = cleanTxt.toLowerCase();
      for (var k = 0; k < popupKeywords.length; k++) {
        if (low === popupKeywords[k] ? true : low.indexOf(popupKeywords[k]) >= 0) {
          if (!foundPopup) foundPopup = cleanTxt;
        }
      }
    }

    var inputs = document.querySelectorAll('textarea, input[type="text"], [contenteditable="true"]');
    var hasBottomComposer = false;
    for (var j = 0; j < inputs.length; j++) {
      var inp = inputs[j];
      var r = inp.getBoundingClientRect();
      if (r.width < 20) continue;
      var top960 = to960Y(r.top);
      var icx = to540X(r.left + r.width / 2);
      var icy = to960Y(r.top + r.height / 2);
      var ph = inp.getAttribute('placeholder') ? inp.getAttribute('placeholder') : 'composer';
      items.push('INPUT[' + ph.substring(0, 40) + ']@(' + icx + ',' + icy + ',top=' + top960 + ')');
      if (top960 >= 175) {
        hasBottomComposer = true;
      }
    }

    var bodyText = document.body ? document.body.innerText : '';
    var pctMatch = bodyText.match(/(\d{1,3})\s*%/);
    var pct = pctMatch ? parseInt(pctMatch[1], 10) : -1;

    var mp4List = [];
    var vids = document.querySelectorAll('video, source, a[href*=".mp4"]');
    for (var v = 0; v < vids.length; v++) {
      var src = vids[v].currentSrc ? vids[v].currentSrc : (vids[v].src ? vids[v].src : vids[v].href);
      if (src && src.indexOf('http') === 0) {
        mp4List.push(src);
      }
    }

    var lowBody = bodyText.toLowerCase();
    var hasModeMismatch = (lowBody.indexOf('nano banana') >= 0) ? true : false;
    var hasLandingCta = (lowBody.indexOf('create with flow') >= 0) ? true : ((lowBody.indexOf('get started') >= 0) ? true : false);

    var state = 'STATE_LANDING';
    if (foundPopup.length > 0) {
      state = 'STATE_POPUP_OBSTACLE';
    } else if (mp4List.length > 0 ? true : (pct === 100)) {
      state = 'STATE_VIDEO_COMPLETED';
    } else if (pct >= 0 && pct < 100) {
      state = 'STATE_RENDERING';
    } else if (hasModeMismatch) {
      state = 'STATE_MODE_MISMATCH';
    } else if (hasBottomComposer) {
      state = 'STATE_COMPOSER_READY';
    } else if (hasLandingCta) {
      state = 'STATE_LANDING';
    }

    return JSON.stringify({
      url: window.location.href,
      title: document.title ? document.title : '',
      heuristicState: state,
      popupObstacleText: foundPopup,
      renderingPercent: pct,
      mp4Urls: mp4List.slice(0, 5),
      summary: items.slice(0, 35).join(' ; ')
    });
  } catch (err) {
    return JSON.stringify({
      url: window.location.href,
      title: '',
      heuristicState: 'STATE_LANDING',
      popupObstacleText: '',
      renderingPercent: -1,
      mp4Urls: [],
      summary: 'DOM_ERR:' + err.message
    });
  }
})();
""".trimIndent()

        val rawJson = evaluateJsSuspend(webView, js)
        return try {
            val obj = JSONObject(rawJson)
            val urls = mutableListOf<String>()
            val arr = obj.optJSONArray("mp4Urls")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i)
                    if (u.isNotBlank()) urls.add(u)
                }
            }
            val state = obj.optString("heuristicState", "STATE_LANDING")
            val summary = obj.optString("summary", "")
            val url = obj.optString("url", "")
            WebDomSnapshot(
                url = url,
                title = obj.optString("title", ""),
                heuristicState = state,
                popupObstacleText = obj.optString("popupObstacleText", ""),
                renderingPercent = obj.optInt("renderingPercent", -1),
                detectedMp4Urls = urls,
                interactiveSummary = summary,
                stateSignature = "${state}_${url.hashCode()}_${summary.hashCode()}"
            )
        } catch (_: Exception) {
            WebDomSnapshot(
                url = "",
                title = "",
                heuristicState = "STATE_LANDING",
                popupObstacleText = "",
                renderingPercent = -1,
                detectedMp4Urls = emptyList(),
                interactiveSummary = "",
                stateSignature = "empty"
            )
        }
    }

    suspend fun autoKillPopupObstacles(webView: WebView): Boolean {
        val snap = inspectLiveDomState(webView)
        if (snap.heuristicState != "STATE_POPUP_OBSTACLE" && snap.popupObstacleText.isBlank()) {
            return false
        }
        val target = if (snap.popupObstacleText.isNotBlank()) snap.popupObstacleText else "Got it"
        val report = execute4SenseSmartClick(webView, target, 270f, 520f)
        delay(850)
        return report.success
    }

    suspend fun enforceGoogleFlowCreditLock(
        webView: WebView,
        durationSeconds: Int
    ): String {
        val targetDurLabel = if (durationSeconds >= 10) "10s" else "8s"
        val js = """
(function() {
  try {
    var clicked = [];
    var all = document.querySelectorAll('button, [role="button"], [role="option"], [role="menuitem"], span, div');
    var clickMatching = function(label) {
      var lowTarget = label.toLowerCase();
      for (var i = 0; i < all.length; i++) {
        var el = all[i];
        var txt = (el.innerText ? el.innerText : '').trim().toLowerCase();
        if (txt === lowTarget) {
          var r = el.getBoundingClientRect();
          if (r.width > 8 && r.height > 8) {
            el.click();
            clicked.push(label);
            return true;
          }
        }
      }
      return false;
    };
    clickMatching('Video');
    clickMatching('9:16');
    clickMatching('$targetDurLabel');
    clickMatching('x1');
    clickMatching('1');
    return 'CREDIT_LOCK_APPLIED:' + clicked.join(',');
  } catch (e) {
    return 'CREDIT_LOCK_ERR:' + e.message;
  }
})();
""".trimIndent()
        return evaluateJsSuspend(webView, js)
    }

    suspend fun typePromptIntoBottomComposerOnce(
        webView: WebView,
        promptText: String
    ): Boolean {
        val escapedPrompt = JSONObject.quote(promptText)
        val js = """
(function() {
  try {
    var vh = window.innerHeight > 0 ? window.innerHeight : 960;
    var fields = document.querySelectorAll('textarea, [contenteditable="true"], input[type="text"]');
    var chosen = null;
    for (var i = 0; i < fields.length; i++) {
      var el = fields[i];
      var rect = el.getBoundingClientRect();
      var top960 = Math.round((rect.top / vh) * 960);
      if (rect.width > 40 && top960 >= 175) {
        chosen = el;
        break;
      }
    }
    if (!chosen && fields.length > 0) {
      chosen = fields[fields.length - 1];
    }
    if (!chosen) return 'NO_COMPOSER';

    chosen.focus();
    var textVal = $escapedPrompt;
    var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value');
    if (nativeSetter && nativeSetter.set && chosen.tagName.toLowerCase() === 'textarea') {
      nativeSetter.set.call(chosen, textVal);
    } else if (chosen.isContentEditable) {
      chosen.innerText = textVal;
    } else {
      chosen.value = textVal;
    }
    chosen.dispatchEvent(new Event('input', { bubbles: true }));
    chosen.dispatchEvent(new Event('change', { bubbles: true }));

    var btns = document.querySelectorAll('button, [role="button"]');
    for (var b = 0; b < btns.length; b++) {
      var btn = btns[b];
      var label = (btn.innerText ? btn.innerText : (btn.getAttribute('aria-label') ? btn.getAttribute('aria-label') : '')).toLowerCase();
      if (label.indexOf('generate') >= 0 ? true : (label.indexOf('create') >= 0 ? true : (label.indexOf('send') >= 0 ? true : (label.indexOf('submit') >= 0)))) {
        btn.click();
        return 'TYPED_AND_SUBMITTED';
      }
    }
    chosen.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    return 'TYPED_AND_ENTER';
  } catch (e) {
    return 'ERR:' + e.message;
  }
})();
""".trimIndent()
        val res = evaluateJsSuspend(webView, js)
        return res.startsWith("TYPED_")
    }

    suspend fun execute4SenseSmartClick(
        webView: WebView,
        targetSelectorOrText: String,
        fallbackX540: Float,
        fallbackY960: Float
    ): ClickExecutionReport {
        val beforeSnap = inspectLiveDomState(webView)
        val escapedTarget = JSONObject.quote(targetSelectorOrText)

        // SENSE-1: Stealth DOM Click + Pointer/Mouse Event Sequence
        val sense1Js = """
(function() {
  try {
    var target = $escapedTarget.trim().toLowerCase();
    if (!target) return '';
    var vw = window.innerWidth > 0 ? window.innerWidth : 540;
    var vh = window.innerHeight > 0 ? window.innerHeight : 960;
    var nodes = document.querySelectorAll('button, a, [role="button"], input, span, div');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var r = el.getBoundingClientRect();
      if (r.width < 8) continue;
      if (r.height < 8) continue;
      var txt = (el.innerText ? el.innerText : (el.getAttribute('aria-label') ? el.getAttribute('aria-label') : '')).trim().toLowerCase();
      if (txt === target ? true : (txt.length > 0 && txt.length < 80 && txt.indexOf(target) >= 0)) {
        var cx = Math.round(((r.left + r.width / 2) / vw) * 540);
        var cy = Math.round(((r.top + r.height / 2) / vh) * 960);
        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(evtName) {
          el.dispatchEvent(new MouseEvent(evtName, { bubbles: true, cancelable: true, view: window }));
        });
        return cx + ',' + cy;
      }
    }
    return '';
  } catch (e) {
    return '';
  }
})();
""".trimIndent()

        val sense1Coords = evaluateJsSuspend(webView, sense1Js)
        if (sense1Coords.contains(",")) {
            val parts = sense1Coords.split(",")
            val sx = parts.getOrNull(0)?.toFloatOrNull() ?: fallbackX540
            val sy = parts.getOrNull(1)?.toFloatOrNull() ?: fallbackY960
            AiOverlayHud.moveBotAndShowTap(sx, sy, "Sense-1 DOM")
            delay(950)
            val afterSense1 = inspectLiveDomState(webView)
            if (afterSense1.stateSignature != beforeSnap.stateSignature) {
                return ClickExecutionReport(
                    success = true,
                    senseUsed = "Sense-1 (Stealth DOM)",
                    targetX = sx,
                    targetY = sy,
                    needsProModelEscalation = false,
                    details = "DOM click verified state transition"
                )
            }
        }

        // SENSE-2: 3x Scaled Hardware Finger Touch (MotionEvent with ±2.4px jitter & curved micro-drag)
        dispatchHumanFingerTouch540x960(webView, fallbackX540, fallbackY960, holdMs = Random.nextLong(70L, 135L))
        AiOverlayHud.moveBotAndShowTap(fallbackX540, fallbackY960, "Sense-2 Touch")
        delay(950)
        val afterSense2 = inspectLiveDomState(webView)
        if (afterSense2.stateSignature != beforeSnap.stateSignature) {
            return ClickExecutionReport(
                success = true,
                senseUsed = "Sense-2 (3x Hardware Finger Touch)",
                targetX = fallbackX540,
                targetY = fallbackY960,
                needsProModelEscalation = false,
                details = "Hardware MotionEvent verified state transition"
            )
        }

        // SENSE-3: Visual Text-Node Range Coordinate Finder (540x960) + Inner Play Icon @(92, 286)
        val rangeCoordsJs = """
(function() {
  try {
    var target = $escapedTarget.trim().toLowerCase();
    var vw = window.innerWidth > 0 ? window.innerWidth : 540;
    var vh = window.innerHeight > 0 ? window.innerHeight : 960;
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
    var node;
    while ((node = walker.nextNode())) {
      var val = (node.nodeValue ? node.nodeValue : '').trim().toLowerCase();
      if (val && target && val.indexOf(target) >= 0) {
        var range = document.createRange();
        range.selectNodeContents(node);
        var r = range.getBoundingClientRect();
        if (r.width > 4 && r.height > 4) {
          var rx = Math.round(((r.left + r.width / 2) / vw) * 540);
          var ry = Math.round(((r.top + r.height / 2) / vh) * 960);
          return rx + ',' + ry;
        }
      }
    }
    return '';
  } catch (e) {
    return '';
  }
})();
""".trimIndent()

        val rangeStr = evaluateJsSuspend(webView, rangeCoordsJs)
        val sense3X: Float
        val sense3Y: Float
        if (rangeStr.contains(",")) {
            val p = rangeStr.split(",")
            sense3X = p.getOrNull(0)?.toFloatOrNull() ?: FLOW_PLAY_CARD_X
            sense3Y = p.getOrNull(1)?.toFloatOrNull() ?: FLOW_PLAY_CARD_Y
        } else {
            sense3X = FLOW_PLAY_CARD_X
            sense3Y = FLOW_PLAY_CARD_Y
        }

        dispatchHumanFingerTouch540x960(webView, sense3X, sense3Y, holdMs = 115L)
        AiOverlayHud.moveBotAndShowTap(sense3X, sense3Y, "Sense-3 Range/Play")
        delay(950)
        val afterSense3 = inspectLiveDomState(webView)
        if (afterSense3.stateSignature != beforeSnap.stateSignature) {
            return ClickExecutionReport(
                success = true,
                senseUsed = "Sense-3 (Visual Text-Range XY)",
                targetX = sense3X,
                targetY = sense3Y,
                needsProModelEscalation = false,
                details = "Visual Range XY tap succeeded"
            )
        }

        // SENSE-4: Offset Hold Tap + Escalation Signal to High-Reasoning Pro Model (gemini-3.8-pro)
        val offsetX = (fallbackX540 + 6f).coerceIn(10f, 530f)
        val offsetY = (fallbackY960 - 6f).coerceIn(10f, 950f)
        dispatchHumanFingerTouch540x960(webView, offsetX, offsetY, holdMs = 240L)
        AiOverlayHud.moveBotAndShowTap(offsetX, offsetY, "Sense-4 Pro Escalate")
        delay(1000)

        return ClickExecutionReport(
            success = true,
            senseUsed = "Sense-4 (Offset Hold + Pro Escalation)",
            targetX = offsetX,
            targetY = offsetY,
            needsProModelEscalation = true,
            details = "Escalating next cognitive cycle to High-Reasoning Pro Model"
        )
    }

    suspend fun dispatchHumanFingerTouch540x960(
        webView: WebView,
        virtualX: Float,
        virtualY: Float,
        holdMs: Long = 95L
    ) = withContext(Dispatchers.Main) {
        try {
            val viewW = webView.width.coerceAtLeast(540).toFloat()
            val viewH = webView.height.coerceAtLeast(960).toFloat()

            val scaleX = viewW / VIRTUAL_WIDTH
            val scaleY = viewH / VIRTUAL_HEIGHT

            val jitterX = (Random.nextFloat() * 4.8f) - 2.4f
            val jitterY = (Random.nextFloat() * 4.8f) - 2.4f

            val realX = ((virtualX + jitterX) * scaleX).coerceIn(2f, viewW - 2f)
            val realY = ((virtualY + jitterY) * scaleY).coerceIn(2f, viewH - 2f)

            val downTime = SystemClock.uptimeMillis()
            val downEvent = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                realX,
                realY,
                0
            )
            webView.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            delay(holdMs / 2)

            val moveTime = SystemClock.uptimeMillis()
            val moveEvent = MotionEvent.obtain(
                downTime,
                moveTime,
                MotionEvent.ACTION_MOVE,
                realX + 1.2f,
                realY - 0.9f,
                0
            )
            webView.dispatchTouchEvent(moveEvent)
            moveEvent.recycle()

            delay(holdMs / 2)

            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(
                downTime,
                upTime,
                MotionEvent.ACTION_UP,
                realX + 1.2f,
                realY - 0.9f,
                0
            )
            webView.dispatchTouchEvent(upEvent)
            upEvent.recycle()
        } catch (_: Exception) {
        }
    }

    suspend fun extractMp4UrlFromDomOrReactFiber(webView: WebView): String {
        val js = """
(function() {
  try {
    var vids = document.querySelectorAll('video, source, a');
    for (var i = 0; i < vids.length; i++) {
      var el = vids[i];
      var u = el.currentSrc ? el.currentSrc : (el.src ? el.src : (el.href ? el.href : ''));
      if (u && u.indexOf('http') === 0 && (u.indexOf('.mp4') >= 0 ? true : (u.indexOf('googlevideo') >= 0 ? true : (u.indexOf('storage.googleapis.com') >= 0)))) {
        return u;
      }
    }
    if (window.performance && window.performance.getEntriesByType) {
      var entries = window.performance.getEntriesByType('resource');
      for (var j = entries.length - 1; j >= 0; j--) {
        var name = entries[j].name ? entries[j].name : '';
        if (name.indexOf('http') === 0 && (name.indexOf('.mp4') >= 0 ? true : (name.indexOf('videoplayback') >= 0))) {
          return name;
        }
      }
    }
    return '';
  } catch (e) {
    return '';
  }
})();
""".trimIndent()
        return evaluateJsSuspend(webView, js)
    }

    suspend fun verifyActionTransition(
        webView: WebView,
        beforeSnapshot: WebDomSnapshot,
        modelName: String,
        clickSense: String,
        actionName: String,
        reasoning: String
    ): ActionVerifyOutcome {
        delay(1000)
        val afterSnapshot = inspectLiveDomState(webView)
        val changed = (afterSnapshot.stateSignature != beforeSnapshot.stateSignature) or
            (afterSnapshot.heuristicState != beforeSnapshot.heuristicState)

        val verifyLabel = if (changed) {
            "VERIFIED (${beforeSnapshot.heuristicState} -> ${afterSnapshot.heuristicState})"
        } else {
            "HOLD (${afterSnapshot.heuristicState})"
        }

        val logLine = "[$modelName • $clickSense] $actionName -> $reasoning -> $verifyLabel"
        return ActionVerifyOutcome(
            stateChanged = changed,
            previousState = beforeSnapshot.heuristicState,
            newState = afterSnapshot.heuristicState,
            summaryLog = logLine
        )
    }
}