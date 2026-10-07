#!/usr/bin/env bash
#
# Copies the biomarker index from the client to the backend.
#
# The client copy is the source of truth: the same folder holds the curated markdown pages,
# and from phase 5 it also feeds the RAG index, so the slugs have to agree with the pages that
# exist. The backend needs its own copy on the classpath to look up a slug for an extracted
# test name without a network call.
#
# BiomarkerIndexSyncTest fails if the two files differ, so running this is how you fix that
# failure. Do not edit the backend copy by hand.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

source_file="$repo_root/client/content/biomarkers/index.json"
target_file="$repo_root/backend/src/main/resources/biomarkers/index.json"

if [[ ! -f "$source_file" ]]; then
	echo "error: missing $source_file" >&2
	exit 1
fi

mkdir -p "$(dirname "$target_file")"
cp "$source_file" "$target_file"

echo "Synced biomarker index:"
echo "  from $source_file"
echo "  to   $target_file"
