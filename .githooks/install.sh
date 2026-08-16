#!/bin/sh
# Install the Salon guardrail hooks into this clone's .git/hooks.
# Run once after cloning:  sh .githooks/install.sh
#
# We copy (not core.hooksPath) so the existing graphify post-commit hook in
# .git/hooks/ keeps working — pointing core.hooksPath here would disable it.
set -e
here=$(cd "$(dirname "$0")" && pwd)
root=$(git rev-parse --show-toplevel)
cp "$here/pre-commit" "$root/.git/hooks/pre-commit"
chmod +x "$root/.git/hooks/pre-commit"
echo "Installed pre-commit guardrail: code changes now require a version bump."
