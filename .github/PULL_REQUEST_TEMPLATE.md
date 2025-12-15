## Summary

<!-- Provide a brief 1-2 sentence summary of what this PR does -->




## Features

<!-- 
  List new features or enhancements added in this PR.
  Delete this section if not applicable.
  
  Format:
  - Brief description of the feature
  - Another feature added
-->

- None


## Bug Fixes

<!-- 
  List bugs that are fixed by this PR.
  Delete this section if not applicable.
  
  Format:
  - Fixed issue where X happened when Y
  - Resolved crash when doing Z
-->

- None


## Breaking Changes

<!-- 
  List any changes that break backwards compatibility.
  Delete this section if not applicable.
  
  Format:
  - Changed `--old-flag` to `--new-flag`
  - Removed deprecated function `oldFunction()`
  - Changed output format from X to Y
-->

- None


## Checklist

- [ ] I have updated the `version` in `build.gradle.kts` (REQUIRED - see below)
- [ ] I have tested these changes locally
- [ ] I have added/updated tests for new functionality
- [ ] I have updated documentation (if applicable)
- [ ] Breaking changes are clearly documented above


<!--

==== IMPORTANT: VERSION UPDATE REQUIRED

Before this PR can be merged, you MUST update the version in
build.gradle.kts to be HIGHER than the current released version.

The PR will fail the version check if you don't update it!

Example in build.gradle.kts:
  version = "0.2.0"  // Update this to be higher than current release

Semantic Versioning Guide:
  - MAJOR version: Breaking changes (e.g., 1.0.0 -> 2.0.0)
  - MINOR version: New features, backwards compatible (e.g., 1.0.0 -> 1.1.0)
  - PATCH version: Bug fixes (e.g., 1.0.0 -> 1.0.1)


==== PR DESCRIPTION GUIDELINES

1. SUMMARY
   - Keep it concise (1-2 sentences)
   - Focus on WHAT changed and WHY, not HOW

2. FEATURES / BUG FIXES / BREAKING CHANGES
   - Use bullet points for each item
   - Start with a verb: "Add", "Fix", "Remove", "Change", "Update"
   - Be specific: "Fix crash when input file is empty" not "Fix bug"
   - Delete sections that don't apply to your PR

3. EXAMPLES

   Good:
   - Add `--threads` flag for parallel sequence alignment
   - Fix memory leak when processing large FASTA files
   - Change `--output` to `--output-dir` for clarity

   Bad:
   - Added new feature
   - Fixed bug
   - Updated code

-->
