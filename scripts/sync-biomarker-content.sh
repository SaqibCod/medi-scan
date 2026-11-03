#!/usr/bin/env bash
#
# Copies the curated biomarker pages, and the index, from the client to the backend.
#
# `client/content/biomarkers/` is the source of truth (CLAUDE.md rule 20). The same folder
# serves the public explainer pages and, from phase 5, feeds the RAG index that chat retrieves
# from. The backend needs its own copy because the Docker build context is `backend/` only -
# nothing outside it exists at image build time, so a page that is not copied in is a page the
# deployed app cannot answer from.
#
# BiomarkerContentSyncTest fails when the two copies differ, so running this is how you fix
# that failure. Never edit the backend copy by hand: it is an output, and the next run of this
# script overwrites it.
#
# The index is delegated to sync-biomarker-index.sh rather than copied again here. That file
# has its own consumer (BiomarkerCatalog, phase 2) and its own test, and two scripts writing
# the same target is how they drift.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

source_dir="$repo_root/client/content/biomarkers"
target_dir="$repo_root/backend/src/main/resources/biomarkers/pages"

if [[ ! -d "$source_dir" ]]; then
	echo "error: missing $source_dir" >&2
	exit 1
fi

shopt -s nullglob
pages=("$source_dir"/*.md)
shopt -u nullglob

if [[ ${#pages[@]} -eq 0 ]]; then
	echo "error: no .md pages found in $source_dir" >&2
	exit 1
fi

# Removed and recreated, not copied over the top. A page deleted from the client has to
# disappear from the backend too, or the index keeps answering from a page that no longer
# exists anywhere a reviewer would look.
rm -rf "$target_dir"
mkdir -p "$target_dir"

for page in "${pages[@]}"; do
	cp "$page" "$target_dir/$(basename "$page")"
done

# The index travels with the pages: a slug in one and not the other is the drift that matters.
"$repo_root/scripts/sync-biomarker-index.sh" >/dev/null

echo "Synced ${#pages[@]} biomarker pages and the index:"
echo "  from $source_dir"
echo "  to   $target_dir"
