<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Kanbn Board Changelog

## [Unreleased]

### Added

- Read-only **Kanbn** tool window showing the board from `.kanbn/index.md` and `.kanbn/tasks/*.md`
- Task cards with name, ID, assignee, tags, state (started / completed), sub-tasks, due date and comment count
- Task detail on click: metadata, markdown description, sub-tasks, relations, comments and history
- Automatic refresh when files in the Kanbn folder change, including changes made outside the IDE (e.g. by AI agents using the kanbn CLI)
- Board switcher for multiple boards in one Kanbn folder
- Hidden columns (`hiddenColumns`) are left out, with their task count shown in the header
