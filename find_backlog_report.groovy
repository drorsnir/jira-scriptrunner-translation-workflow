import groovy.transform.Field
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Backlog Report Script
 *
 * Finds all Hebrew issues that should have been cloned for translation but weren't.
 * Uses the same logic as the main translation script to determine eligibility.
 *
 * OUTPUT:
 * 1. Oldest matching issue for each language
 * 2. Total count for Arabic and Russian
 * 3. List of all issues with subtask counts
 */

@Field static final String PROJECT_KEY = 'KOL'  // Change this to your project key
@Field static final int LOOKBACK_MONTHS = 5  // Only look back this many months
@Field static final int MAX_ISSUES_TO_PROCESS = 0  // 0 = unlimited, set to limit processing (e.g., 200)
@Field static final String CLONED_LABEL = 'cloned_for_translation'
@Field static final boolean DEBUG = false  // Set to true to see detailed debug for each issue

// Cache for subtask details to avoid duplicate fetches
@Field static final Map<String, Map<String, Object>> subtaskCache = [:]

@Field static final Map<String, String> FIELD_IDS = [
    LANGUAGE: 'customfield_10305',
    ARTICLE_TRANSLATED_TO: 'customfield_11711',
    COMPLEXITY: 'customfield_11602',
    CONTENT_AREA: 'customfield_11691',
    LINK: 'customfield_11689',
    PROBLEM: 'customfield_11632',
    PAGE_TITLE: 'customfield_10201',
    LAW_PUBLISH_DATE: 'customfield_11690',
    LAW_FULL_NAME: 'customfield_11703',
    LAW_DUE_DATE: 'customfield_11646',
    LAW_DATE: 'customfield_11645',
    ISSUE_SOURCE: 'customfield_11678'
]

@Field static final Map<String, String> ISSUE_TYPE_MAPPING = [
    'עדכוני צ\'טבוט': 'עדכוני צ\'טבוט (תרגום)',
    'שינוי חקיקה (עברית)': 'שינוי חקיקה (תרגום)',
    'הצעת שינוי (עברית)': 'הצעת שינוי (תרגום)'
]

@Field static final List<String> EXCLUDED_RESOLUTIONS = [
    'לא תוקן - המידע באתר נכון',
    'לא תוקן - הנושא לא מכוסה ב"כל זכות"',
    'לא תוקן - פניה אישית / הצעה לשינוי חקיקה / תלונה / אחר',
    'לא רלוונטי'
]

@Field static final Map<String, String> LANGUAGE_IDS = [
    'Hebrew':  '10127',
    'Arabic':  '10128',
    'English': '10129',
    'Other':   '10130',
    'Russian': '10338'
]

// Results storage
@Field static final List<Map<String, Object>> arabicIssues = []
@Field static final List<Map<String, Object>> russianIssues = []

Map<String, Object> getSubtaskDetails(String subtaskKey) {
    // Check cache first
    if (subtaskCache.containsKey(subtaskKey)) {
        return subtaskCache[subtaskKey]
    }

    try {
        def response = get("/rest/api/3/issue/${subtaskKey}").asObject(Map)
        if (response['status'] == 200) {
            Map<String, Object> subtaskData = response['body'] as Map<String, Object>
            subtaskCache[subtaskKey] = subtaskData
            return subtaskData
        }
    } catch (Exception e) {
        println "ERROR: Failed to fetch subtask ${subtaskKey}: ${e.message}"
    }
    return null
}

// Check if parent issue qualifies (without checking subtasks)
boolean parentQualifiesForTranslation(Map<String, Object> issue, String lang) {
    Map<String, Object> fields = issue['fields'] as Map<String, Object>

    // Check if issue is resolved
    if (!fields['resolution']) {
        return false
    }

    // Check resolution exclusions
    String resolutionName = (fields['resolution'] as Map<String, Object>)['name'] as String
    if (EXCLUDED_RESOLUTIONS.contains(resolutionName)) {
        return false
    }

    List<String> labels = fields['labels'] as List<String> ?: []
    // Check for cloning exclusion labels
    if (labels.contains('לא_לתרגום') || labels.contains(CLONED_LABEL)) {
        return false
    }

    String issueTypeName = (fields['issuetype'] as Map<String, Object>)['name'] as String

    def languageField = fields[FIELD_IDS.LANGUAGE]
    String issueLanguage = null
    if (languageField instanceof Map) {
        issueLanguage = (languageField['value'] ?: '') as String
    }

    def translatedToField = fields[FIELD_IDS.ARTICLE_TRANSLATED_TO]
    List<String> translatedTo = []
    if (translatedToField instanceof List) {
        translatedTo = translatedToField.collect {
            def value = it['value'] ?: it['id']
            value as String
        }
    } else if (translatedToField instanceof Map) {
        def value = translatedToField['value'] ?: translatedToField['id']
        translatedTo = [value as String]
    }

    return issueLanguage == 'Hebrew' &&
           ISSUE_TYPE_MAPPING.containsKey(issueTypeName) &&
           translatedTo.contains(lang)
}

boolean needsTranslation(Map<String, Object> issue, String lang) {
    String issueKey = issue['key'] as String
    Map<String, Object> fields = issue['fields'] as Map<String, Object>

    if (DEBUG) println "  Checking ${issueKey} for ${lang}..."

    // First check if parent qualifies (fast check, no API calls)
    if (parentQualifiesForTranslation(issue, lang)) {
        if (DEBUG) println "    ✅ Parent qualifies"
        return true
    }

    // Only check subtasks if parent doesn't qualify
    List<Map<String, Object>> subtasks = fields['subtasks'] as List<Map<String, Object>> ?: []
    if (subtasks.isEmpty()) {
        if (DEBUG) println "    ❌ No subtasks to check"
        return false
    }

    boolean hasTranslatableSubtask = subtasks.any { Map<String, Object> subtask ->
        Map<String, Object> subtaskFull = getSubtaskDetails(subtask['key'] as String)
        if (!subtaskFull) return false

        Map<String, Object> subtaskFields = subtaskFull['fields'] as Map<String, Object>
        String subtaskTypeName = (subtaskFields['issuetype'] as Map<String, Object>)['name'] as String

        def subtaskLanguageField = subtaskFields[FIELD_IDS.LANGUAGE]
        String subtaskLanguage = null
        if (subtaskLanguageField instanceof Map) {
            subtaskLanguage = (subtaskLanguageField['value'] ?: '') as String
        }

        def subtaskTranslatedToField = subtaskFields[FIELD_IDS.ARTICLE_TRANSLATED_TO]
        List<String> subtaskTranslatedTo = []
        if (subtaskTranslatedToField instanceof List) {
            subtaskTranslatedTo = subtaskTranslatedToField.collect {
                def value = it['value'] ?: it['id']
                value as String
            }
        } else if (subtaskTranslatedToField instanceof Map) {
            def value = subtaskTranslatedToField['value'] ?: subtaskTranslatedToField['id']
            subtaskTranslatedTo = [value as String]
        }

        return subtaskTypeName == 'משימת משנה' &&
               subtaskLanguage == 'Hebrew' &&
               subtaskTranslatedTo.contains(lang)
    }

    if (DEBUG) println "    ${hasTranslatableSubtask ? '✅ Has translatable subtask' : '❌ No translatable subtasks'}"
    return hasTranslatableSubtask
}

// Count translatable subtasks for BOTH languages in one pass
Map<String, Integer> countTranslatableSubtasksForBothLanguages(Map<String, Object> issue) {
    Map<String, Object> fields = issue['fields'] as Map<String, Object>
    List<Map<String, Object>> subtasks = fields['subtasks'] as List<Map<String, Object>> ?: []

    int arCount = 0
    int ruCount = 0

    subtasks.each { Map<String, Object> subtask ->
        Map<String, Object> subtaskFull = getSubtaskDetails(subtask['key'] as String)
        if (!subtaskFull) return

        Map<String, Object> subtaskFields = subtaskFull['fields'] as Map<String, Object>
        String subtaskTypeName = (subtaskFields['issuetype'] as Map<String, Object>)['name'] as String

        if (subtaskTypeName != 'משימת משנה') {
            return
        }

        def subtaskLanguageField = subtaskFields[FIELD_IDS.LANGUAGE]
        String subtaskLanguage = null
        if (subtaskLanguageField instanceof Map) {
            subtaskLanguage = (subtaskLanguageField['value'] ?: '') as String
        }

        if (subtaskLanguage != 'Hebrew') {
            return
        }

        def subtaskTranslatedToField = subtaskFields[FIELD_IDS.ARTICLE_TRANSLATED_TO]
        List<String> subtaskTranslatedTo = []
        if (subtaskTranslatedToField instanceof List) {
            subtaskTranslatedTo = subtaskTranslatedToField.collect {
                def value = it['value'] ?: it['id']
                value as String
            }
        } else if (subtaskTranslatedToField instanceof Map) {
            def value = subtaskTranslatedToField['value'] ?: subtaskTranslatedToField['id']
            subtaskTranslatedTo = [value as String]
        }

        if (subtaskTranslatedTo.contains('ar')) arCount++
        if (subtaskTranslatedTo.contains('ru')) ruCount++
    }

    return [ar: arCount, ru: ruCount]
}

// Main execution
try {
    println "=" * 80
    println "BACKLOG REPORT - Unprocessed Translation Issues"
    println "=" * 80
    println "Generated: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss'))}"
    println "Project: ${PROJECT_KEY}"

    // Calculate cutoff date
    LocalDateTime cutoffDate = LocalDateTime.now().minusMonths(LOOKBACK_MONTHS)
    String cutoffDateStr = cutoffDate.format(DateTimeFormatter.ofPattern('yyyy-MM-dd'))
    println "Looking back: ${LOOKBACK_MONTHS} months (from ${cutoffDateStr})"
    if (MAX_ISSUES_TO_PROCESS > 0) {
        println "Max issues to process: ${MAX_ISSUES_TO_PROCESS}"
    }
    println ""

    // Search for potential candidates
    // Note: We get ALL resolved Hebrew issues and filter in code because JQL label negation is tricky
    String jql = """project = ${PROJECT_KEY}
                    AND issueType in ("עדכוני צ'טבוט", "שינוי חקיקה (עברית)", "הצעת שינוי (עברית)")
                    AND resolution is not EMPTY
                    AND resolved >= "${cutoffDateStr}"
                    AND ${FIELD_IDS.LANGUAGE} = "${LANGUAGE_IDS['Hebrew']}"
                    ORDER BY resolved ASC"""

    if (DEBUG) {
        println "JQL Query:"
        println jql
        println ""
    }
    println "Searching for candidate issues..."

    // Use POST /rest/api/3/search/jql with nextPageToken pagination
    List<Map<String, Object>> candidates = []
    String nextPageToken = null
    int pageCount = 0

    while (true) {
        pageCount++

        Map<String, Object> requestBody = [
            jql: jql,
            fields: ['key', 'summary', 'created', 'resolved', 'resolution', 'issuetype',
                     'labels', 'subtasks', FIELD_IDS.LANGUAGE, FIELD_IDS.ARTICLE_TRANSLATED_TO]
        ]

        if (nextPageToken) {
            requestBody['nextPageToken'] = nextPageToken
        }

        def searchResponse = post('/rest/api/3/search/jql')
            .header('Content-Type', 'application/json')
            .body(requestBody)
            .asObject(Map)

        if (searchResponse['status'] != 200) {
            println "ERROR: Failed to search for issues"
            println "Status: ${searchResponse['status']}"
            println "Body: ${searchResponse['body']}"
            return
        }

        Map<String, Object> body = searchResponse['body'] as Map<String, Object>
        List<Map<String, Object>> issues = body['issues'] as List<Map<String, Object>>

        candidates.addAll(issues)
        println "  Page ${pageCount}: Fetched ${issues.size()} issues (total so far: ${candidates.size()})..."

        // Check for next page token
        nextPageToken = body['nextPageToken'] as String
        if (!nextPageToken) {
            break
        }
    }

    println "Found ${candidates.size()} candidate issues. Analyzing...\n"

    // Clear cache at start
    subtaskCache.clear()

    // Limit processing if configured
    int issuesToProcess = MAX_ISSUES_TO_PROCESS > 0 ?
        Math.min(candidates.size(), MAX_ISSUES_TO_PROCESS) : candidates.size()

    if (issuesToProcess < candidates.size()) {
        println "Processing first ${issuesToProcess} of ${candidates.size()} issues\n"
        candidates = candidates.take(issuesToProcess)
    }

    // Analyze each candidate for both languages
    candidates.eachWithIndex { Map<String, Object> issue, int idx ->
        String issueKey = issue['key'] as String
        Map<String, Object> fields = issue['fields'] as Map<String, Object>
        String summary = fields['summary'] as String
        String resolved = (fields['resolved'] ?: fields['resolutiondate'] ?: fields['created']) as String

        // Show progress every 50 issues
        if ((idx + 1) % 50 == 0) {
            println "  Analyzed ${idx + 1} of ${issuesToProcess} issues... (cache: ${subtaskCache.size()} subtasks)"
        }

        // Check both languages
        boolean needsAr = needsTranslation(issue, 'ar')
        boolean needsRu = needsTranslation(issue, 'ru')

        // If either language needs translation, count subtasks once for both
        if (needsAr || needsRu) {
            Map<String, Integer> subtaskCounts = countTranslatableSubtasksForBothLanguages(issue)

            if (needsAr) {
                arabicIssues << [
                    key: issueKey,
                    summary: summary,
                    resolved: resolved,
                    subtaskCount: subtaskCounts['ar']
                ]
            }

            if (needsRu) {
                russianIssues << [
                    key: issueKey,
                    summary: summary,
                    resolved: resolved,
                    subtaskCount: subtaskCounts['ru']
                ]
            }
        }
    }

    println "  Analysis complete. (${subtaskCache.size()} unique subtasks fetched)\n"

    // Sort by resolved date
    arabicIssues.sort { it.resolved }
    russianIssues.sort { it.resolved }

    // OUTPUT REPORT
    println "=" * 80
    println "SUMMARY"
    println "=" * 80

    // 1. Oldest matching issue for each language
    println "\n1. OLDEST UNPROCESSED ISSUES:"
    println "-" * 40

    if (arabicIssues) {
        def oldest = arabicIssues[0]
        println "Arabic (ar):"
        println "  Issue: ${oldest.key}"
        println "  Summary: ${oldest.summary}"
        println "  Resolved: ${oldest.resolved}"
        println "  Translatable subtasks: ${oldest.subtaskCount}"
    } else {
        println "Arabic (ar): No unprocessed issues found"
    }

    println ""

    if (russianIssues) {
        def oldest = russianIssues[0]
        println "Russian (ru):"
        println "  Issue: ${oldest.key}"
        println "  Summary: ${oldest.summary}"
        println "  Resolved: ${oldest.resolved}"
        println "  Translatable subtasks: ${oldest.subtaskCount}"
    } else {
        println "Russian (ru): No unprocessed issues found"
    }

    // 2. Total counts
    println "\n" + "-" * 40
    println "2. TOTAL COUNTS:"
    println "-" * 40
    println "Arabic (ar): ${arabicIssues.size()} issues"
    println "Russian (ru): ${russianIssues.size()} issues"

    // 3. Full lists with subtask counts
    println "\n" + "-" * 40
    println "3. DETAILED LISTS:"
    println "-" * 40

    if (arabicIssues) {
        println "\nARABIC (ar) - ${arabicIssues.size()} issues:"
        println ""
        arabicIssues.eachWithIndex { issue, idx ->
            println "  ${idx + 1}. ${issue.key} - ${issue.subtaskCount} translatable subtasks"
            println "     ${issue.summary}"
            println "     Resolved: ${issue.resolved}"
            println ""
        }
    } else {
        println "\nARABIC (ar): No unprocessed issues"
    }

    if (russianIssues) {
        println "\nRUSSIAN (ru) - ${russianIssues.size()} issues:"
        println ""
        russianIssues.eachWithIndex { issue, idx ->
            println "  ${idx + 1}. ${issue.key} - ${issue.subtaskCount} translatable subtasks"
            println "     ${issue.summary}"
            println "     Resolved: ${issue.resolved}"
            println ""
        }
    } else {
        println "\nRUSSIAN (ru): No unprocessed issues"
    }

    println "=" * 80
    println "REPORT COMPLETE"
    println "=" * 80

} catch (Exception e) {
    println "\nFATAL ERROR: ${e.message}"
    e.printStackTrace()
    throw e
}
