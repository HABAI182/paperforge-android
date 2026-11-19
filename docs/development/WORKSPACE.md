# Workspace hygiene

Use `scripts/workspace_hygiene.py` to audit regenerable local outputs that Git ignores.

## Where things go
- **Round work**: `work/roundN/` (inputs, device outputs, comparison images, the round report). One folder per round,
  not new top-level folders per experiment.
- **Test builds**: `work/test-builds/<version>/`; the delivery copy goes to the Desktop. Published releases live on
  GitHub; do not keep local copies of them.
- **Training scenes**: write `REGENERATE.md` (exact command, generator commit) and `deleted-bins.sha256` next to every
  scene set; remove `images.bin`/`masks.bin` when the round's training is done. They rebuild byte-identically.
- **Device outputs**: pull into the round folder (`work/roundN/dev/`), not into shared folders.
- **Scratch**: session scratch directories are temporary; anything worth keeping moves into `work/roundN/`.

## What accumulates, and the check
`python scripts/workspace_hygiene.py` reports, and with `--apply <category>` removes:
- `emulator`: the emulator's `-read-only` instances leave temporary overlay disks in `%LOCALAPPDATA%\Temp\AndroidEmulator`
  at startup. A running emulator's files are locked and skipped.
- `scenes`: scene bins that already carry a `REGENERATE.md`.
- `worktrees`: build outputs of git worktrees other than the main checkout.
- `distributions`: `build/distributions` older than 14 days.

Never remove: sources, git history, credentials and signing keys, original documents and photos, models in use,
regression and truth sets (`work/page-net/truth`, `work/independent-eval`, `work/round*/corpus`), Desktop deliveries,
toolchains (`work/toolchain`), and the main checkout's build caches during a round (a cold native build is long).
