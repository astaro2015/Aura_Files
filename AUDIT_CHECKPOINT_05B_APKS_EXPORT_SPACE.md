# Audit checkpoint 05B — APKS export symmetry / free-space guard

Follow-up to checkpoint 05.

Finding: incoming APKS was now bounded, but export of an installed SPLIT app still had no total-size ceiling/preflight even though the receiving parser enforces an 8 GiB payload ceiling. A huge installed package or nearly-full cache volume could fail late after writing a large temporary file.

Fix:
- Sum base + split APK sizes before export using overflow-safe arithmetic.
- Enforce the same 8 GiB payload ceiling as import.
- Require expected output bytes + 128 MiB reserve in the app cache volume before starting export.
