import groovy.transform.Field
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ==================== CONFIGURATION ====================

@Field static final String MODE = 'PROCESS'  // 'REPORT' or 'PROCESS'
@Field static final boolean DRY_RUN = true  // Set to false to actually process issues (only applies in PROCESS mode)
@Field static final boolean DEBUG = false  // Set to true for detailed debug output

@Field static final String PROJECT_KEY = 'KOL'
@Field static final int LOOKBACK_MONTHS = 8
@Field static final int MAX_ISSUES_PER_RUN = 1  // In PROCESS mode, limit issues processed per run

@Field static final String CLONED_LABEL = 'cloned_for_translation'
@Field static final String BACKLOG_LABEL = 'תרגום_נדחה'  // Issues identified as backlog needing translation
@Field static final String LOCK_LABEL = 'locked_by_automation'  // Temporary lock to prevent race conditions

@Field static final int TIMEOUT_SAFETY_SECONDS = 210  // Stop processing at 210s (leave 30s buffer before 240s limit)
@Field static long scriptStartTime = System.currentTimeMillis()

// ==================== FIELD IDS & MAPPINGS ====================

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

@Field static final Map<String, String> LANGUAGE_CODE_TO_NAME = [
    'he': 'Hebrew',
    'ar': 'Arabic',
    'en': 'English',
    'ru': 'Russian'
]

// ==================== CACHING ====================

@Field static final Map<String, Map<String, Object>> subtaskCache = [:]

// ==================== TIMING & SAFETY ====================

boolean isTimeRemaining() {
    long elapsedSeconds = (System.currentTimeMillis() - scriptStartTime) / 1000
    return elapsedSeconds < TIMEOUT_SAFETY_SECONDS
}

long getElapsedSeconds() {
    return (System.currentTimeMillis() - scriptStartTime) / 1000
}

// ==================== LOGGING ====================

void log(String level, String message, Map<String, Object> details = null) {
    if (level == 'INFO' && !DEBUG) {
        return
    }

    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
    String logMessage = "${timestamp} [${level}] ${message}"
    if (details) {
        logMessage += "\nDetails: ${groovy.json.JsonOutput.toJson(details)}"
    }

    if (level == 'ERROR') {
        logger.error(logMessage)
    } else if (level == 'WARN') {
        logger.warn(logMessage)
    } else {
        logger.info(logMessage)
    }
}

// ==================== UTILITY FUNCTIONS ====================

Map<String, Object> getSubtaskDetails(String subtaskKey) {
    // Check cache first
    if (subtaskCache.containsKey(subtaskKey)) {
        log('INFO', "Cache hit for subtask ${subtaskKey}")
        return subtaskCache[subtaskKey]
    }

    try {
        def response = get("/rest/api/3/issue/${subtaskKey}").asObject(Map)
        if (response['status'] == 200) {
            Map<String, Object> subtaskData = response['body'] as Map<String, Object>
            subtaskCache[subtaskKey] = subtaskData
            log('INFO', "Cached subtask ${subtaskKey}")
            return subtaskData
        } else {
            log('ERROR', "Failed to fetch subtask details", [
                subtaskKey: subtaskKey,
                status: response['status']
            ])
            return null
        }
    } catch (Exception e) {
        log('ERROR', "Exception while fetching subtask details", [
            subtaskKey: subtaskKey,
            error: e.message
        ])
        return null
    }
}

boolean parentQualifiesForTranslation(Map<String, Object> issue, String lang) {
    Map<String, Object> fields = issue['fields'] as Map<String, Object>

    if (!fields['resolution']) return false

    String resolutionName = (fields['resolution'] as Map<String, Object>)['name'] as String
    if (EXCLUDED_RESOLUTIONS.contains(resolutionName)) return false

    List<String> labels = fields['labels'] as List<String> ?: []
    if (labels.contains('לא_לתרגום') || labels.contains(CLONED_LABEL)) return false

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

        if (subtaskTypeName != 'משימת משנה') return

        def subtaskLanguageField = subtaskFields[FIELD_IDS.LANGUAGE]
        String subtaskLanguage = null
        if (subtaskLanguageField instanceof Map) {
            subtaskLanguage = (subtaskLanguageField['value'] ?: '') as String
        }

        if (subtaskLanguage != 'Hebrew') return

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

boolean needsTranslation(Map<String, Object> issue, String lang) {
    // Check parent first
    if (parentQualifiesForTranslation(issue, lang)) {
        return true
    }

    // Check subtasks
    Map<String, Integer> subtaskCounts = countTranslatableSubtasksForBothLanguages(issue)
    return subtaskCounts[lang] > 0
}

// ==================== ISSUE CREATION (DRY RUN AWARE) ====================

List<String> getRequiredFieldsForIssueType(String issueType) {
    switch (issueType) {
        case 'הצעת שינוי (עברית)':
            return [
                FIELD_IDS.LINK,
                FIELD_IDS.ARTICLE_TRANSLATED_TO,
                FIELD_IDS.PAGE_TITLE,
                FIELD_IDS.CONTENT_AREA,
                FIELD_IDS.COMPLEXITY,
                FIELD_IDS.PROBLEM,
                FIELD_IDS.ISSUE_SOURCE
            ]
        case 'שינוי חקיקה (עברית)':
            return [
                FIELD_IDS.LINK,
                FIELD_IDS.ARTICLE_TRANSLATED_TO,
                FIELD_IDS.LAW_PUBLISH_DATE,
                FIELD_IDS.LAW_FULL_NAME,
                FIELD_IDS.LAW_DUE_DATE,
                FIELD_IDS.LAW_DATE
            ]
        case 'משימת משנה':
            return [
                FIELD_IDS.LINK,
                FIELD_IDS.ARTICLE_TRANSLATED_TO,
                FIELD_IDS.PAGE_TITLE,
                FIELD_IDS.CONTENT_AREA
            ]
        default:
            return [
                FIELD_IDS.LINK,
                FIELD_IDS.ARTICLE_TRANSLATED_TO
            ]
    }
}

Map<String, Object> copyFields(Map<String, Object> sourceIssue) {
    Map<String, Object> sourceFields = sourceIssue['fields'] as Map<String, Object>
    String issueType = (sourceFields['issuetype'] as Map<String, Object>)['name'] as String

    Map<String, Object> fields = [
        'summary': sourceFields['summary'] as String,
        'description': sourceFields['description']
    ]

    List<String> requiredFields = getRequiredFieldsForIssueType(issueType)
    requiredFields.each { String fieldId ->
        if (sourceFields[fieldId] != null) {
            fields[fieldId] = sourceFields[fieldId]
        }
    }

    fields = fields.findAll { it.value != null } as Map<String, Object>

    log('INFO', "Fields copied successfully", [
        sourceKey: sourceIssue['key'] as String,
        issueType: issueType,
        copiedFields: fields.keySet()
    ])

    return fields
}

Map<String, Object> createIssue(Map<String, Object> originalIssue, String lang, boolean isSubtask = false, String parentKey = null) {
    String originalKey = originalIssue['key'] as String

    try {
        Map<String, Object> fields = copyFields(originalIssue)

        String sourceProjectKey = (originalIssue['fields']['project'] as Map<String, Object>)['key'] as String
        fields['project'] = [key: sourceProjectKey]

        String languageName = LANGUAGE_CODE_TO_NAME[lang]
        fields[FIELD_IDS.LANGUAGE] = [id: LANGUAGE_IDS[languageName]]
        fields['reporter'] = null

        if (isSubtask) {
            fields['parent'] = [key: parentKey]
            fields['issuetype'] = [name: 'משימת משנה']
        } else {
            String originalTypeName = (originalIssue['fields']['issuetype'] as Map<String, Object>)['name'] as String
            fields['issuetype'] = [name: ISSUE_TYPE_MAPPING[originalTypeName]]
        }

        if (DRY_RUN) {
            log('INFO', "[DRY RUN] Would create issue", [
                sourceKey: originalKey,
                language: lang,
                isSubtask: isSubtask,
                parentKey: parentKey,
                issueType: fields['issuetype']['name'],
                fields: fields.keySet()
            ])

            // Return simulated success
            return [
                'key': "${originalKey}-${lang}-DRYRUN",
                'status': 'success',
                'dryRun': true
            ] as Map<String, Object>
        }

        // Live mode - actually create the issue
        def response = post('/rest/api/3/issue')
            .header('Content-Type', 'application/json')
            .body([fields: fields])
            .asObject(Map)

        if (response['status'] == 201) {
            String newIssueKey = response['body']['key'] as String

            // Create clone link
            def linkResponse = post('/rest/api/3/issueLink')
                .header('Content-Type', 'application/json')
                .body([
                    type: [name: 'Cloners'],
                    inwardIssue: [key: newIssueKey],
                    outwardIssue: [key: originalKey]
                ])
                .asObject(Map)

            if (linkResponse['status'] != 201) {
                log('ERROR', "Failed to create clone link", [
                    originalKey: originalKey,
                    newKey: newIssueKey,
                    status: linkResponse['status']
                ])
            }

            // Add the backlog label to the newly created translation issue
            def newIssueLabels = [BACKLOG_LABEL]
            def newIssueLabelResponse = put("/rest/api/3/issue/${newIssueKey}")
                .header('Content-Type', 'application/json')
                .body([
                    fields: [
                        labels: newIssueLabels
                    ]
                ])
                .asObject(Map)

            if (newIssueLabelResponse['status'] != 204) {
                log('WARN', "Failed to add backlog label to new issue", [
                    issueKey: newIssueKey,
                    status: newIssueLabelResponse['status']
                ])
            }

            return [
                'key': newIssueKey,
                'status': 'success',
                'body': response['body']
            ] as Map<String, Object>

        } else {
            log('ERROR', "Failed to create issue", [
                originalKey: originalKey,
                language: lang,
                status: response['status'],
                responseBody: response['body']
            ])

            return [
                'status': 'error',
                'message': "Failed to create issue",
                'response': response['body']
            ] as Map<String, Object>
        }
    } catch (Exception e) {
        log('ERROR', "Exception while creating issue", [
            originalKey: originalKey,
            error: e.message
        ])

        return [
            'status': 'error',
            'message': e.message,
            'exception': e.class.name
        ] as Map<String, Object>
    }
}

Map<String, Object> processIssue(Map<String, Object> currentIssue, String lang) {
    String issueKey = currentIssue['key'] as String
    Map<String, Object> fields = currentIssue['fields'] as Map<String, Object>
    String issueTypeName = (fields['issuetype'] as Map<String, Object>)['name'] as String

    log('INFO', "Processing issue for translation", [
        issueKey: issueKey,
        language: lang
    ])

    if (needsTranslation(currentIssue, lang)) {
        log('INFO', "Creating translation issue", [
            sourceKey: issueKey,
            language: lang
        ])

        Map<String, Object> result = createIssue(currentIssue, lang)
        List<Tuple2<String, String>> subtaskMappings = []

        if (result['status'] == 'success') {
            String newIssueKey = result['key'] as String
            log('INFO', "Successfully created translation issue", [
                sourceKey: issueKey,
                newKey: newIssueKey,
                language: lang
            ])

            // Process subtasks
            List<Map<String, Object>> subtasks = fields['subtasks'] as List<Map<String, Object>> ?: []

            subtasks.each { Map<String, Object> subtask ->
                String subtaskKey = subtask['key'] as String
                Map<String, Object> subtaskFull = getSubtaskDetails(subtaskKey)
                if (subtaskFull) {
                    Map<String, Object> subtaskFields = subtaskFull['fields'] as Map<String, Object>
                    def translatedToField = subtaskFields[FIELD_IDS.ARTICLE_TRANSLATED_TO]
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

                    if (translatedTo.contains(lang)) {
                        log('INFO', "Creating translation subtask", [
                            sourceKey: subtaskKey,
                            parentKey: newIssueKey,
                            language: lang
                        ])

                        Map<String, Object> subtaskResult = createIssue(subtaskFull, lang, true, newIssueKey)

                        if (subtaskResult['status'] == 'success') {
                            subtaskMappings << new Tuple2(subtaskKey, subtaskResult['key'])
                            log('INFO', "Successfully created translation subtask", [
                                sourceKey: subtaskKey,
                                newKey: subtaskResult['key'],
                                parentKey: newIssueKey,
                                language: lang
                            ])
                        } else {
                            log('ERROR', "Failed to create translation subtask", [
                                sourceKey: subtaskKey,
                                parentKey: newIssueKey,
                                language: lang,
                                error: subtaskResult['message']
                            ])
                        }
                    }
                }
            }

            // Print summary
            String mode = DRY_RUN ? "[DRY RUN] " : ""
            println """
${mode}Source Issue: ${issueKey} ("${issueTypeName}")
${mode}Cloned to ${newIssueKey} ("${ISSUE_TYPE_MAPPING[issueTypeName]}"), language: ${lang}""" +
            (subtaskMappings ? "\n${mode}Cloned subtasks: ${subtaskMappings.collect { source, target -> "${source} → ${target}" }.join(', ')}" : "")

            return [status: 'success', language: lang]
        } else {
            log('ERROR', "Failed to create translation issue", [
                sourceKey: issueKey,
                language: lang,
                error: result['message']
            ])
            return [status: 'error', language: lang, message: result['message']]
        }
    } else {
        log('INFO', "No translation needed", [
            issueKey: issueKey,
            language: lang
        ])
        return [status: 'skipped', language: lang]
    }
}

// ==================== MAIN EXECUTION ====================

println """
${'=' * 80}
BACKLOG PROCESSOR - ${MODE} MODE${MODE == 'PROCESS' ? (DRY_RUN ? ' (DRY RUN)' : ' (LIVE)') : ''}
${'=' * 80}
Configuration:
  - Mode: ${MODE} (${MODE == 'REPORT' ? 'report only' : (DRY_RUN ? 'preview' : 'live processing')})
  - Project: ${PROJECT_KEY}
  - Lookback: ${LOOKBACK_MONTHS} months
  - Max issues per run: ${MAX_ISSUES_PER_RUN}${MODE == 'PROCESS' ? " (fetch limit: ${MAX_ISSUES_PER_RUN * 3})" : ''}
  - Backlog label: ${BACKLOG_LABEL}
  - Timeout safety: ${TIMEOUT_SAFETY_SECONDS}s
  - Debug: ${DEBUG}
${'=' * 80}
"""

if (MODE == 'REPORT') {
    println """
ℹ️  REPORT MODE ACTIVE
This will only analyze and report issues needing translation.
No changes will be made to Jira.
Set MODE = 'PROCESS' to start processing issues.
${'=' * 80}
"""
} else if (DRY_RUN) {
    println """
⚠️  DRY RUN MODE ACTIVE ⚠️
This will preview what would be created without making changes.
Set DRY_RUN = false at the top of the script to actually process issues.
${'=' * 80}
"""
}

try {
    // Step 1: Find all candidate issues
    println "\n[Step 1] Finding candidate issues..."

    String lookbackDate = LocalDateTime.now().minusMonths(LOOKBACK_MONTHS).format(DateTimeFormatter.ISO_LOCAL_DATE)

    // Build JQL with exclusions to reduce candidates upfront
    // Use double quotes consistently and escape internal double quotes
    String issueTypesJql = ISSUE_TYPE_MAPPING.keySet().collect { String type ->
        String escaped = type.replaceAll('"', '\\\\"')  // Escape double quotes only
        return "\"${escaped}\""
    }.join(', ')
    String excludedResolutionsJql = EXCLUDED_RESOLUTIONS.collect { String res ->
        String escaped = res.replaceAll('"', '\\\\"')  // Escape double quotes only
        return "\"${escaped}\""
    }.join(', ')

    // Exclude issues that have already been cloned, marked as not for translation, or locked
    // In PROCESS mode, also exclude issues that were already processed by this backlog script
    String excludeLabels = MODE == 'PROCESS'
        ? "\"לא_לתרגום\", \"${CLONED_LABEL}\", \"${BACKLOG_LABEL}\", \"${LOCK_LABEL}\""
        : "\"לא_לתרגום\", \"${CLONED_LABEL}\", \"${LOCK_LABEL}\""

    String labelFilter = "(labels not in(${excludeLabels}) or labels is EMPTY)"

    String jql = """project = ${PROJECT_KEY}
AND resolved >= "${lookbackDate}"
AND issuetype in (${issueTypesJql})
AND ${labelFilter}
AND resolution not in (${excludedResolutionsJql})""".replaceAll('\n', ' ')

    println "  Mode: ${MODE}"
    println "  Issue types JQL: ${issueTypesJql}"
    println "  Excluded resolutions JQL: ${excludedResolutionsJql}"
    println "  JQL: ${jql}"

    List<Map<String, Object>> candidates = []
    String nextPageToken = null
    int pageCount = 0

    // In PROCESS mode, we only need enough issues to satisfy MAX_ISSUES_PER_RUN
    // Fetch a bit extra to account for filtering, but not all issues
    Integer fetchLimit = (MODE == 'PROCESS') ? (MAX_ISSUES_PER_RUN * 3) : null

    while (true) {
        pageCount++

        Map<String, Object> requestBody = [
            jql: jql,
            fields: ['key', 'summary', 'created', 'resolved', 'resolution', 'issuetype',
                     'labels', 'subtasks', 'project', FIELD_IDS.LANGUAGE, FIELD_IDS.ARTICLE_TRANSLATED_TO]
        ]

        if (fetchLimit != null) {
            requestBody['maxResults'] = fetchLimit
        }

        if (nextPageToken) {
            requestBody['nextPageToken'] = nextPageToken
        }

        def searchResponse = post('/rest/api/3/search/jql')
            .header('Content-Type', 'application/json')
            .body(requestBody)
            .asObject(Map)

        if (searchResponse['status'] != 200) {
            throw new Exception("Failed to search for issues: ${searchResponse['body']}")
        }

        Map<String, Object> body = searchResponse['body'] as Map<String, Object>
        List<Map<String, Object>> issues = body['issues'] as List<Map<String, Object>>

        candidates.addAll(issues)
        println "  Page ${pageCount}: Fetched ${issues.size()} issues (total so far: ${candidates.size()})..."

        // In PROCESS mode, stop fetching once we have enough candidates
        if (fetchLimit != null && candidates.size() >= fetchLimit) {
            println "  Reached fetch limit of ${fetchLimit} issues in PROCESS mode."
            break
        }

        nextPageToken = body['nextPageToken'] as String
        if (!nextPageToken) {
            break
        }
    }

    println "  Found ${candidates.size()} candidate issues."

    // Step 2: Filter and analyze
    println "\n[Step 2] Analyzing issues for translation needs..."

    int issuesToProcess = candidates.size()  // Analyze all found issues in REPORT mode
    println "  Will analyze ${issuesToProcess} of ${candidates.size()} issues."

    List<Map<String, Object>> arabicIssues = []
    List<Map<String, Object>> russianIssues = []

    candidates.take(issuesToProcess).eachWithIndex { Map<String, Object> issue, int idx ->
        String issueKey = issue['key'] as String
        Map<String, Object> fields = issue['fields'] as Map<String, Object>
        String summary = fields['summary'] as String

        if ((idx + 1) % 50 == 0) {
            println "  Analyzed ${idx + 1} of ${issuesToProcess} issues... (cache: ${subtaskCache.size()} subtasks)"
        }

        boolean needsAr = needsTranslation(issue, 'ar')
        boolean needsRu = needsTranslation(issue, 'ru')

        if (needsAr || needsRu) {
            Map<String, Integer> subtaskCounts = countTranslatableSubtasksForBothLanguages(issue)

            if (needsAr) {
                arabicIssues << [
                    key: issueKey,
                    summary: summary,
                    subtaskCount: subtaskCounts['ar'],
                    issue: issue
                ]
            }

            if (needsRu) {
                russianIssues << [
                    key: issueKey,
                    summary: summary,
                    subtaskCount: subtaskCounts['ru'],
                    issue: issue
                ]
            }
        }
    }

    println "  Analysis complete."
    println "  Arabic translations needed: ${arabicIssues.size()}"
    println "  Russian translations needed: ${russianIssues.size()}"

    // Step 3: Report
    println """
${'=' * 80}
BACKLOG REPORT
${'=' * 80}
"""

    if (arabicIssues.isEmpty() && russianIssues.isEmpty()) {
        println "✅ No backlog issues found! All caught up."
    } else {
        if (!arabicIssues.isEmpty()) {
            println "\n📋 Arabic Translations (${arabicIssues.size()} issues):"
            arabicIssues.eachWithIndex { item, idx ->
                println "  ${idx + 1}. ${item.key} - ${item.summary} (${item.subtaskCount} subtasks)"
            }
        }

        if (!russianIssues.isEmpty()) {
            println "\n📋 Russian Translations (${russianIssues.size()} issues):"
            russianIssues.eachWithIndex { item, idx ->
                println "  ${idx + 1}. ${item.key} - ${item.summary} (${item.subtaskCount} subtasks)"
            }
        }
    }

    // Step 4: Process issues (only in PROCESS mode)
    if (MODE == 'PROCESS' && (!arabicIssues.isEmpty() || !russianIssues.isEmpty())) {
        println """
${'=' * 80}
${DRY_RUN ? 'PREVIEW' : 'PROCESSING'} ISSUES
${'=' * 80}
"""

        int processedCount = 0
        int successfullyProcessed = 0
        int totalToProcess = Math.min(MAX_ISSUES_PER_RUN, arabicIssues.size() + russianIssues.size())

        // Group issues by key to process all languages for each issue together
        Map<String, Map<String, Object>> issuesByKey = [:]
        arabicIssues.each { item ->
            String key = item.key as String
            if (!issuesByKey.containsKey(key)) {
                issuesByKey[key] = [
                    key: key,
                    issue: item.issue,
                    summary: item.summary,
                    languages: []
                ]
            }
            issuesByKey[key]['languages'] << 'ar'
        }
        russianIssues.each { item ->
            String key = item.key as String
            if (!issuesByKey.containsKey(key)) {
                issuesByKey[key] = [
                    key: key,
                    issue: item.issue,
                    summary: item.summary,
                    languages: []
                ]
            }
            issuesByKey[key]['languages'] << 'ru'
        }

        List<Map<String, Object>> issuesList = issuesByKey.values().toList()

        for (Map<String, Object> issueData : issuesList) {
            if (processedCount >= totalToProcess) {
                println "\nReached maximum of ${MAX_ISSUES_PER_RUN} issues per run."
                break
            }

            if (!isTimeRemaining()) {
                println "\n⚠️ Time limit approaching (${getElapsedSeconds()}s elapsed). Stopping processing."
                println "Processed ${processedCount} of ${issuesList.size()} issues before timeout."
                break
            }

            processedCount++
            String issueKey = issueData.key as String
            List<String> languages = issueData.languages as List<String>
            Map<String, Object> issue = issueData.issue as Map<String, Object>

            println "\n[${processedCount}/${Math.min(totalToProcess, issuesList.size())}] Processing ${issueKey} for ${languages.join(', ')}... (${getElapsedSeconds()}s elapsed)"

            // Add lock label to prevent other automation from processing this issue
            if (!DRY_RUN) {
                List<String> lockLabels = issue['fields']['labels'] as List<String> ?: []
                if (!lockLabels.contains(LOCK_LABEL)) {
                    lockLabels.add(LOCK_LABEL)
                    def lockResponse = put("/rest/api/3/issue/${issueKey}")
                        .header('Content-Type', 'application/json')
                        .body([fields: [labels: lockLabels]])
                        .asObject(Map)

                    if (lockResponse['status'] == 204) {
                        log('INFO', "Added lock label to issue", [issueKey: issueKey])
                    } else {
                        log('WARN', "Failed to add lock label", [issueKey: issueKey, status: lockResponse['status']])
                    }
                }
            }

            boolean allSuccessful = true
            List<String> successfulLanguages = []

            // Process each language
            languages.each { String lang ->
                Map<String, Object> result = processIssue(issue, lang)
                if (result['status'] == 'success') {
                    successfulLanguages << lang
                } else {
                    allSuccessful = false
                }
            }

            // If all languages processed successfully, update labels on original issue
            if (allSuccessful && !successfulLanguages.isEmpty() && !DRY_RUN) {
                List<String> currentLabels = issue['fields']['labels'] as List<String> ?: []

                // Remove lock label
                currentLabels.remove(LOCK_LABEL)

                // Add permanent markers
                if (!currentLabels.contains(CLONED_LABEL)) {
                    currentLabels.add(CLONED_LABEL)
                }
                if (!currentLabels.contains(BACKLOG_LABEL)) {
                    currentLabels.add(BACKLOG_LABEL)
                }

                def labelResponse = put("/rest/api/3/issue/${issueKey}")
                    .header('Content-Type', 'application/json')
                    .body([
                        fields: [
                            labels: currentLabels
                        ]
                    ])
                    .asObject(Map)

                if (labelResponse['status'] == 204) {
                    log('INFO', "Updated labels on original issue", [
                        issueKey: issueKey,
                        languages: successfulLanguages,
                        addedLabels: [CLONED_LABEL, BACKLOG_LABEL],
                        removedLabels: [LOCK_LABEL]
                    ])
                    successfullyProcessed++
                } else {
                    log('WARN', "Failed to update labels on original issue", [
                        issueKey: issueKey,
                        status: labelResponse['status']
                    ])
                }
            } else if (allSuccessful && DRY_RUN) {
                println "  [DRY RUN] Would remove '${LOCK_LABEL}' and add '${CLONED_LABEL}', '${BACKLOG_LABEL}' labels to ${issueKey}"
                successfullyProcessed++
            } else if (!allSuccessful && !DRY_RUN) {
                // Processing failed, remove lock so it can be retried
                List<String> currentLabels = issue['fields']['labels'] as List<String> ?: []
                if (currentLabels.contains(LOCK_LABEL)) {
                    currentLabels.remove(LOCK_LABEL)
                    put("/rest/api/3/issue/${issueKey}")
                        .header('Content-Type', 'application/json')
                        .body([fields: [labels: currentLabels]])
                        .asObject(Map)
                    log('WARN', "Removed lock label after failed processing", [issueKey: issueKey])
                }
            }
        }

        println "\n${'=' * 80}"
        println "Processing summary:"
        println "  - Issues processed: ${processedCount}"
        println "  - Successfully completed: ${successfullyProcessed}"
        println "  - Elapsed time: ${getElapsedSeconds()}s"
        println "${'=' * 80}"
    } else if (MODE == 'REPORT') {
        println "\n${'=' * 80}"
        println "REPORT MODE - No processing performed"
        println "Set MODE = 'PROCESS' to actually create translations"
        println "${'=' * 80}"
    }

    println """
${'=' * 80}
${MODE == 'REPORT' ? 'REPORT' : (DRY_RUN ? 'DRY RUN' : 'PROCESSING')} COMPLETE
${'=' * 80}
Summary:
  - Mode: ${MODE}
  - Total issues analyzed: ${issuesToProcess}
  - Arabic translations found: ${arabicIssues.size()}
  - Russian translations found: ${russianIssues.size()}
  - Subtasks cached: ${subtaskCache.size()}
"""

    if (MODE == 'REPORT') {
        println """
Next steps:
1. Set MODE = 'PROCESS' to start processing issues
2. Keep DRY_RUN = true for preview first
3. Set DRY_RUN = false to actually create translations
"""
    } else if (DRY_RUN) {
        println """
To actually process these issues, set DRY_RUN = false at the top of the script.
"""
    } else {
        println """
Issues have been processed. Run the script again in PROCESS mode to continue
processing remaining issues (it will skip issues labeled '${BACKLOG_LABEL}').
"""
    }

} catch (Exception e) {
    println """
${'=' * 80}
ERROR
${'=' * 80}
Exception: ${e.message}
Exception Type: ${e.class.name}

Stack trace (top 10):
${e.stackTrace.take(10).collect { "  ${it}" }.join('\n')}
${'=' * 80}
"""
    throw e
}
