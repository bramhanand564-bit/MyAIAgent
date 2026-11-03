#!/usr/bin/env python3
from pathlib import Path
import re
import shutil
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "android").resolve()
if not (root / "app" / "src" / "main" / "AndroidManifest.xml").exists():
    raise SystemExit("React Native Android scaffold is missing its app manifest.")

required_files = [
    root / "gradlew",
    root / "gradle" / "wrapper" / "gradle-wrapper.jar",
    root / "settings.gradle",
    root / "app" / "build.gradle",
    root / "app" / "src" / "main" / "AndroidManifest.xml",
]
missing = [str(path) for path in required_files if not path.is_file()]
if missing:
    raise SystemExit("Generated Android scaffold is incomplete: " + ", ".join(missing))

main_app_candidates = list((root / "app" / "src" / "main" / "java").rglob("MainApplication.kt"))
if len(main_app_candidates) != 1:
    raise SystemExit("Expected exactly one generated MainApplication.kt file.")
main_app = main_app_candidates[0]
source = main_app.read_text(encoding="utf-8")
marker = "PackageList(this).packages.apply {"
if marker not in source:
    raise SystemExit("Could not locate React Native PackageList registration block.")
if "add(PdfTextPackage())" not in source:
    source = source.replace(marker, marker + "\n        add(PdfTextPackage())", 1)
main_app.write_text(source, encoding="utf-8")

package_dir = main_app.parent
for filename in (
    "PdfTextModule.kt",
    "PdfTextPackage.kt",
    "ResumableDownloadModule.kt",
    "SystemSettingsModule.kt",
):
    source_file = Path("native/android") / filename
    if not source_file.is_file():
        raise SystemExit("Missing native source: " + str(source_file))
    shutil.copy2(source_file, package_dir / filename)

gradle_file = root / "app" / "build.gradle"
gradle = gradle_file.read_text(encoding="utf-8")
dependency = 'implementation("com.tom-roush:pdfbox-android:2.0.27.0")'
if dependency not in gradle:
    match = re.search(r"dependencies\s*\{", gradle)
    if not match:
        raise SystemExit("Could not locate dependencies block in app/build.gradle.")
    gradle = gradle[:match.end()] + "\n    " + dependency + gradle[match.end():]
gradle_file.write_text(gradle, encoding="utf-8")

manifest_file = root / "app" / "src" / "main" / "AndroidManifest.xml"
manifest = manifest_file.read_text(encoding="utf-8")
permissions = [
    "android.permission.INTERNET",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.WAKE_LOCK",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
]
for permission in permissions:
    entry = '<uses-permission android:name="' + permission + '" />'
    if entry not in manifest:
        manifest = manifest.replace("<application", entry + "\n    <application", 1)
service = """        <service
            android:name="com.asterinet.react.bgactions.RNBackgroundActionsTask"
            android:exported="false"
            android:foregroundServiceType="dataSync" />"""
if "com.asterinet.react.bgactions.RNBackgroundActionsTask" not in manifest:
    manifest = manifest.replace("</application>", service + "\n    </application>", 1)
manifest_file.write_text(manifest, encoding="utf-8")

gradle_properties = root / "gradle.properties"
properties = gradle_properties.read_text(encoding="utf-8")
properties = re.sub(r"(?m)^newArchEnabled=.*$", "newArchEnabled=false", properties)
if "newArchEnabled=" not in properties:
    properties += "\nnewArchEnabled=false\n"
gradle_properties.write_text(properties, encoding="utf-8")

proguard = root / "app" / "proguard-rules.pro"
rules = proguard.read_text(encoding="utf-8") if proguard.exists() else ""
for rule in (
    "-keep class com.rnllama.** { *; }",
    "-keep class com.tom_roush.pdfbox.** { *; }",
):
    if rule not in rules:
        rules += "\n" + rule
proguard.write_text(rules.strip() + "\n", encoding="utf-8")

print("Android scaffold verified and patched with PDF, resumable download, system settings and scheduler permissions.")
