# Aether notice

RdxVpn can bundle the Aether engine (version 2.4.0, from
CluvexStudio/Aether) as a prebuilt binary inside the app. The binary is
fetched at build time by `tools/get-cores.sh` and is not committed to
this repository.

Aether is licensed under the GNU Affero General Public License, version
3 only (AGPL-3.0-only). Bundling its binary inside the app makes the
distributed app a combined work under the AGPL, so the app's full
corresponding source must stay offered under the AGPL. That is
acceptable here because this repository is already public and
AGPL-licensed, but it means the source can never be closed later.

This note does not change the app's own license. The app's license is
the `LICENSE` file at the repository root, which this note leaves
untouched.
