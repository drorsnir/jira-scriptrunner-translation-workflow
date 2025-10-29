# Jira ScriptRunner Scripts

This directory contains ScriptRunner scripts for automated translation workflow in Jira.

## Scripts Overview

### 1. `clone_for_translation.groovy` (Main Translation Script)

**Type:** Post-function / Listener
**Trigger:** Issue updated event
**Purpose:** Automatically creates translation clones when Hebrew issues are resolved

**Key Features:**
- Runs as a Jira listener on issue updates
- Checks if resolved Hebrew issues need translation to Arabic/Russian
- Creates translated issue clones with proper field mapping
- Creates translated subtask clones when needed
- Links clones using "Cloners" relationship
- Adds `cloned_for_translation` label to originals
- Uses `locked_by_automation` label to prevent race conditions
- Email notifications for errors (via PostMark API)
- Comprehensive error tracking and logging

**Configuration:**
- Set `DEBUG = true` for detailed logging
- Configure email settings: `NOTIFICATION_EMAIL`, `POSTMARK_API_KEY`, `POSTMARK_FROM_EMAIL`
- Set `SEND_EMAIL_NOTIFICATIONS = false` to disable emails
- Set `FAIL_ON_ERRORS = false` to continue execution despite errors

**Exclusions:**
- Issues with `לא_לתרגום` label (not for translation)
- Issues with `cloned_for_translation` label (already cloned)
- Issues with `locked_by_automation` label (being processed by another script)
- Specific resolutions (see `EXCLUDED_RESOLUTIONS`)

### 2. `process_backlog.groovy` (Backlog Processor)

**Type:** Manual execution script
**Purpose:** Process translation backlog from when the main script was malfunctioning

**Two Modes:**

#### REPORT Mode (`MODE = 'REPORT'`)
- Analyzes and reports all issues needing translation
- Shows count of issues requiring Arabic/Russian translation
- Shows subtask counts per issue
- No changes made to Jira
- Fast execution (just analysis)

#### PROCESS Mode (`MODE = 'PROCESS'`)
- Creates translation clones for backlog issues
- Processes issues in safe batches (configurable)
- Has timeout safety (stops at 210s, leaving 30s buffer before 240s limit)
- Can run in dry-run mode (`DRY_RUN = true`) for preview
- Adds three labels:
  - `locked_by_automation` - temporary lock during processing
  - `cloned_for_translation` - permanent marker for successful cloning
  - `תרגום_נדחה` - permanent marker identifying backlog issues

**Configuration:**
```groovy
@Field static final String MODE = 'REPORT'           // 'REPORT' or 'PROCESS'
@Field static final boolean DRY_RUN = true           // Preview mode in PROCESS
@Field static final String PROJECT_KEY = 'KOL'       // Project to process
@Field static final int LOOKBACK_MONTHS = 8          // How far back to search
@Field static final int MAX_ISSUES_PER_RUN = 1       // Batch size in PROCESS mode
@Field static final int TIMEOUT_SAFETY_SECONDS = 210 // Stop before timeout
```

**Workflow:**
1. **First run** - REPORT mode to see scope:
   ```groovy
   MODE = 'REPORT'
   ```
   Shows all issues needing translation

2. **Preview processing** - PROCESS mode with dry run:
   ```groovy
   MODE = 'PROCESS'
   DRY_RUN = true
   ```
   Preview what would be created

3. **Process batches** - Live processing:
   ```groovy
   MODE = 'PROCESS'
   DRY_RUN = false
   MAX_ISSUES_PER_RUN = 10
   ```
   Run repeatedly until backlog is cleared

**Race Condition Prevention:**
- Excludes issues with `locked_by_automation` in JQL
- Adds lock label immediately when starting to process an issue
- Removes lock and adds permanent labels after successful processing
- Removes lock on failure so issue can be retried
- Both scripts (`clone_for_translation.groovy` and `process_backlog.groovy`) respect the lock

### 3. `test_email.groovy` (Email Testing)

**Type:** Manual execution script
**Purpose:** Test email notification configuration

Simple script to verify PostMark API configuration and email delivery.

### 4. `find_backlog_report.groovy` (Deprecated)

**Status:** ⚠️ Superseded by `process_backlog.groovy` REPORT mode
**Purpose:** Old backlog analysis script

This script is no longer needed. Use `process_backlog.groovy` with `MODE = 'REPORT'` instead.

## Labels Used

### Permanent Labels
- **`cloned_for_translation`** - Issue has been successfully cloned for translation
- **`תרגום_נדחה`** - Issue was part of backlog and processed by `process_backlog.groovy`
- **`לא_לתרגום`** - Issue should not be translated (manual exclusion)

### Temporary Labels
- **`locked_by_automation`** - Issue is currently being processed by automation (prevents race conditions)

## Field IDs

All scripts use these custom field IDs:

```groovy
LANGUAGE: 'customfield_10305'
ARTICLE_TRANSLATED_TO: 'customfield_11711'
COMPLEXITY: 'customfield_11602'
CONTENT_AREA: 'customfield_11691'
LINK: 'customfield_11689'
PROBLEM: 'customfield_11632'
PAGE_TITLE: 'customfield_10201'
LAW_PUBLISH_DATE: 'customfield_11690'
LAW_FULL_NAME: 'customfield_11703'
LAW_DUE_DATE: 'customfield_11646'
LAW_DATE: 'customfield_11645'
ISSUE_SOURCE: 'customfield_11678'
```

## Issue Type Mapping

Hebrew issue types are mapped to translation types:

| Hebrew Type | Translation Type |
|-------------|------------------|
| עדכוני צ'טבוט | עדכוני צ'טבוט (תרגום) |
| שינוי חקיקה (עברית) | שינוי חקיקה (תרגום) |
| הצעת שינוי (עברית) | הצעת שינוי (תרגום) |
| משימת משנה | משימת משנה |

## Language Mapping

| Code | Full Name | Language ID | Translated To ID |
|------|-----------|-------------|------------------|
| he | Hebrew | 10127 | 10334 |
| ar | Arabic | 10128 | 10333 |
| ru | Russian | 10338 | 10335 |
| en | English | 10129 | 10336 |
| am | Amharic | - | 10337 |

## Troubleshooting

### Duplicate Translations Created

**Cause:** Both `clone_for_translation.groovy` (listener) and `process_backlog.groovy` processing the same issue simultaneously.

**Solution:** The locking mechanism should prevent this. Check that:
1. Both scripts have the `LOCK_LABEL` constant defined
2. Both scripts check for and respect the `locked_by_automation` label
3. Labels are being added/removed correctly (check issue history)

### Script Timeout (240s limit)

**Cause:** Processing too many issues or complex issues with many subtasks.

**Solution:**
1. Reduce `MAX_ISSUES_PER_RUN` in `process_backlog.groovy`
2. Script will automatically stop at 210s (configurable via `TIMEOUT_SAFETY_SECONDS`)
3. Run the script multiple times to process batches

### Issues Not Being Found

**Cause:** JQL filter excluding issues incorrectly.

**Solution:**
1. Check labels on the issue (should not have `cloned_for_translation`, `לא_לתרגום`, or `locked_by_automation`)
2. Check resolution (should not be in `EXCLUDED_RESOLUTIONS`)
3. Check issue type (must be one of the three translatable types)
4. Check language field (must be Hebrew)
5. Check "תרגומים לערך" field (must contain target language)

### Lock Label Stuck

**Cause:** Script crashed or was interrupted while processing.

**Solution:**
Manually remove the `locked_by_automation` label from the affected issue.

## Testing

See `TESTING_GUIDE.md` for detailed testing procedures.

## Development Notes

### JQL Query Construction

- Always use **double quotes** for string values in JQL
- Escape internal double quotes with `\"`
- Hebrew text with apostrophes (like `צ'טבוט`) is fine inside double quotes
- Use `replaceAll('"', '\\\\"')` to escape quotes in Groovy

Example:
```groovy
String issueTypesJql = ISSUE_TYPE_MAPPING.keySet().collect { String type ->
    String escaped = type.replaceAll('"', '\\\\"')
    return "\"${escaped}\""
}.join(', ')
```

### Performance Optimization

- Use subtask caching (`Map<String, Map<String, Object>> subtaskCache`)
- Short-circuit evaluation (check parent before fetching subtasks)
- Combine operations in single API call when possible
- Filter aggressively in JQL before fetching issues

### Error Handling

- Track errors in `EXECUTION_ERRORS` list
- Mark errors as `critical: true/false`
- Send summary email at end of execution
- Continue processing despite non-critical errors (configurable)
