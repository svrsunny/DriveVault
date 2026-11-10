# Contributing

Thanks for helping. DriveVault is small and maintained by one person, so focused changes are easiest to review.

## Reporting bugs and ideas

- **Security problems:** never in a public issue; see [SECURITY.md](SECURITY.md).
- **Bugs:** open an issue with the bug template. Say which version and platform (Windows / Linux distro / Android
  version), what you did, what happened and what you expected. **Never attach your vault, `vault.json`, your recovery
  key, real file names or an `activity.log` from a real drive.** The demo vault (`python tools/demo.py`) is a safe way to
  reproduce problems and take screenshots.
- **Ideas:** open an issue with the feature template and describe the problem you want solved.

## Changing code

1. Set up the tools in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
2. Keep the change focused and follow the style of the surrounding code.
3. Run the tests: `python tests/run_all.py` (or `--quick` without the Android toolchain) and make sure they pass.
4. Open a pull request using the template.

### Rules that keep vaults safe

- **Existing vaults must keep opening.** Format changes must be additive and optional, and go into
  [docs/FORMAT.md](docs/FORMAT.md), the Python code (`desktop/app/vaultcore.py`), the Kotlin code
  (`android/core/.../Vault.kt`) **and** the compatibility test (`tests/make_compat_vault.py`, `CompatTest.kt`,
  `tests/check_compat_vault.py`) in the same pull request.
- **No home-made crypto.** Use `cryptography` in Python and `javax.crypto` or Bouncy Castle on Android.
- Don't remove features or change how existing features behave without discussing it in an issue first.
- Never commit keystores, `local.properties`, APKs, vault folders or logs (`.gitignore` covers them).

## License

By contributing you agree that your contribution is released under the [MIT License](LICENSE).
