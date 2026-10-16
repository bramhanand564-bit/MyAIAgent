# Working Code Preservation Rules

## Rule 1 — Lock working code

The existing code copied from `Auradroidaai` into `MyAIAgent` is the protected working baseline.

- Do not delete, rename, overwrite, reformat, or refactor existing working files just to make a change easier.
- Do not modify `Auradroidaai`. It is the untouched source of truth.
- Make future improvements in `MyAIAgent` only.
- Prefer additive changes: new files, new functions, adapters, or isolated modules that preserve existing behavior.
- If changing an existing file is genuinely necessary, make the smallest targeted change and preserve unrelated code and behavior.
- Before a change, inspect the relevant code and its callers. Do not assume a feature is unused.
- After a change, build and run relevant checks; compare against the baseline and verify that existing features still work.
- Never claim a build or feature is working until the relevant test has actually passed.
- If a change breaks a working feature, stop and revert/fix that change before continuing.
- Keep changes scoped; do not bundle unrelated cleanup with feature work.

## Change checklist

1. Identify the exact feature and files involved.
2. Preserve all unrelated working code.
3. Add or make the smallest necessary modification in `MyAIAgent`.
4. Run the available build/tests.
5. Report exactly what changed, what passed, and what remains unverified.

These rules apply to all future work on `MyAIAgent`.
