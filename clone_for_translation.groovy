import groovy.transform.Field
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Field static final boolean DEBUG = false  // Set to true for detailed debug output

@Field static final String CLONED_LABEL = 'cloned_for_translation'

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


@Field static final Map<String, String> TRANSLATED_TO_IDS = [
    'ar': '10333',
    'he': '10334',
    'ru': '10335',
    'en': '10336',
    'am': '10337'
]

@Field static final Map<String, String> TRANSLATED_TO_CODES = [
    '10333': 'ar',
    '10334': 'he',
    '10335': 'ru',
    '10336': 'en',
    '10337': 'am'
]

@Field static final Map<String, String> LANGUAGE_IDS = [
    'Hebrew':  '10127',
    'Arabic':  '10128',
    'English': '10129',
    'Other':   '10130',
    'Russian': '10338'
]

@Field static final Map<String, String> LANGUAGE_NAMES = [
    '10127': 'Hebrew',
    '10128': 'Arabic',
    '10129': 'English',
    '10130': 'Other',
    '10338': 'Russian'
]

// Helper method to convert between language codes and full names
@Field static final Map<String, String> LANGUAGE_CODE_TO_NAME = [
    'he': 'Hebrew',
    'ar': 'Arabic',
    'en': 'English',
    'ru': 'Russian'
]

@Field static final String PROJECT_KEY = "KOL"  // Replace with your actual project key

void validateFields(Map<String, Object> issue) {
    List<String> missingFields = []
    Map<String, Object> fields = issue['fields'] as Map<String, Object>
    
    FIELD_IDS.each { String fieldName, String fieldId ->
        if (!fields.containsKey(fieldId)) {
            missingFields.add(fieldName)
        }
    }
    
    if (!missingFields.empty) {
        String errorMsg = "Missing required custom fields: ${missingFields.join(', ')}"
        log('ERROR', errorMsg, [issueKey: issue['key'] as String])
        throw new Exception(errorMsg)
    }
}

void printTranslationSummary(String sourceIssue, String sourceName, String targetIssue, String targetName, String language, List<Tuple2<String, String>> subtaskMappings = []) {
    println """
Source Issue: ${sourceIssue} ("${sourceName}")
Cloned to ${targetIssue} ("${targetName}"), language: ${language}""" +
    (subtaskMappings ? "\nCloned subtasks: ${subtaskMappings.collect { source, target -> "${source} → ${target}" }.join(', ')}" : "")
}

void log(String level, String message, Map<String, Object> details = null) {
    // Always log errors and warnings, only log INFO if debug is on
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

Map<String, Object> getSubtaskDetails(String subtaskKey) {
    try {
        def response = get("/rest/api/3/issue/${subtaskKey}").asObject(Map)
        if (response['status'] == 200) {
            return response['body'] as Map<String, Object>
        } else {
            log('ERROR', "Failed to fetch subtask details", [
                subtaskKey: subtaskKey,
                status: response['status'],
                body: response['body']
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

boolean needsTranslation(Map<String, Object> issue, String lang) {
    String issueKey = issue['key'] as String
    try {
        validateFields(issue)
        
        Map<String, Object> fields = issue['fields'] as Map<String, Object>
        
        log('INFO', "=== Translation Check for ${issueKey} to ${lang} ===")

        // Check if issue is resolved
        if (!fields['resolution']) {
            log('WARN', "Issue excluded from translation - not yet resolved", [
                issueKey: issueKey,
                language: lang
            ])
            return false
        }

        // Check resolution exclusions
        String resolutionName = (fields['resolution'] as Map<String, Object>)['name'] as String
        if (EXCLUDED_RESOLUTIONS.contains(resolutionName)) {
            log('WARN', "Issue excluded from translation due to resolution", [
                issueKey: issueKey,
                language: lang,
                resolution: resolutionName
            ])
            return false
        }

        List<String> labels = fields['labels'] as List<String> ?: []
        // Check for cloning exclusion labels
        if (labels.contains('לא_לתרגום') || labels.contains(CLONED_LABEL)) {
            log('WARN', "Issue excluded from translation due to exclusion label", [
                issueKey: issueKey,
                language: lang,
                labels: labels
            ])
            return false
        }
        String issueTypeName = (fields['issuetype'] as Map<String, Object>)['name'] as String
        log('INFO', """Issue Type: ${issueTypeName}
        Has Type Mapping: ${ISSUE_TYPE_MAPPING.containsKey(issueTypeName)}
        Mapped Type: ${ISSUE_TYPE_MAPPING[issueTypeName]}""")

        def languageField = fields[FIELD_IDS.LANGUAGE]
        String issueLanguage = null
        if (languageField instanceof Map) {
            issueLanguage = (languageField['value'] ?: '') as String
        }
        log("INFO", "Issue Language: ${issueLanguage}");

        def translatedToField = fields[FIELD_IDS.ARTICLE_TRANSLATED_TO]
        List<String> translatedTo = []
        if (translatedToField instanceof List) {
            translatedTo = translatedToField.collect { it['value'] as String }
        }
        log('INFO', """Translated To Field: ${translatedToField}
        Translated To List: ${translatedTo}
        Contains ${lang}: ${translatedTo.contains(lang)}""")

        boolean shouldTranslate = issueLanguage == 'Hebrew' &&
                                ISSUE_TYPE_MAPPING.containsKey(issueTypeName) &&
                                translatedTo.contains(lang)

        log('INFO', """Should Translate: ${shouldTranslate}
=== End Translation Check ===\n""")

        if (shouldTranslate) {
            return true
        }

        // Check subtasks before deciding final exclusion
        List<Map<String, Object>> subtasks = fields['subtasks'] as List<Map<String, Object>> ?: []
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
                subtaskTranslatedTo = subtaskTranslatedToField.collect { it['value'] as String }
            }

            return subtaskTypeName == 'משימת משנה' &&
                   subtaskLanguage == 'Hebrew' &&
                   subtaskTranslatedTo.contains(lang)
        }

        if (!hasTranslatableSubtask) {
            List<String> reasons = []
            if (issueLanguage != 'Hebrew') reasons.add("language is ${issueLanguage} (not Hebrew)".toString())
            if (!ISSUE_TYPE_MAPPING.containsKey(issueTypeName)) reasons.add("issue type ${issueTypeName} not in mapping".toString())
            if (!translatedTo.contains(lang)) reasons.add("not marked for translation to ${lang}".toString())

            log('WARN', "Issue and its subtasks excluded from translation", [
                issueKey: issueKey,
                language: lang,
                reasons: reasons
            ])
        }

        return hasTranslatableSubtask

    } catch (Exception e) {
        log('ERROR', "ERROR in needsTranslation for ${issueKey}: ${e.message}")
        throw e
    }
}

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
    try {
        Map<String, Object> sourceFields = sourceIssue['fields'] as Map<String, Object>
        String issueType = (sourceFields['issuetype'] as Map<String, Object>)['name'] as String

        // Start with required fields
        Map<String, Object> fields = [
            'summary': sourceFields['summary'] as String,
            'description': sourceFields['description']
        ]

        // Add all fields required for this issue type
        List<String> requiredFields = getRequiredFieldsForIssueType(issueType)
        requiredFields.each { String fieldId ->
            if (sourceFields[fieldId] != null) {
                fields[fieldId] = sourceFields[fieldId]
            }
        }

        // Remove null values
        fields = fields.findAll { it.value != null } as Map<String, Object>

        log('INFO', "Fields copied successfully", [
            sourceKey: sourceIssue['key'] as String,
            issueType: issueType,
            copiedFields: fields.keySet()
        ])

        return fields
    } catch (Exception e) {
        log('ERROR', "Exception while copying fields", [
            sourceKey: sourceIssue['key'] as String,
            error: e.message
        ])
        throw e
    }
}

Map<String, Object> createIssue(Map<String, Object> originalIssue, String lang, boolean isSubtask = false, String parentKey = null) {
    try {
        Map<String, Object> fields = copyFields(originalIssue)

        // Set project field explicitly
        fields['project'] = [key: PROJECT_KEY]

        // Set only the language field using full name ID
        String languageName = LANGUAGE_CODE_TO_NAME[lang]
        log('INFO', """Language name for ${lang}: ${languageName}
Language ID from mapping: ${LANGUAGE_IDS[languageName]}""")

        fields[FIELD_IDS.LANGUAGE] = [
            id: LANGUAGE_IDS[languageName]
        ]

        fields['reporter'] = null

        if (isSubtask) {
            fields['parent'] = [key: parentKey]
            fields['issuetype'] = [name: 'משימת משנה']
        } else {
            String originalTypeName = (originalIssue['fields']['issuetype'] as Map<String, Object>)['name'] as String
            fields['issuetype'] = [name: ISSUE_TYPE_MAPPING[originalTypeName]]
        }

        // Log the entire payload we're about to send
        def requestPayload = [fields: fields]
        log('INFO', """=== API Request Payload ===
URL: /rest/api/3/issue
Method: POST
Headers: Content-Type: application/json
Body:
${groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(requestPayload))}
=== End API Request Payload ===\n""")

        log('INFO', """=== Field Values Detail ===
${fields.collect { key, value -> "${key}: (${value?.getClass()?.name}) ${groovy.json.JsonOutput.toJson(value)}" }.join('\n')}
=== End Field Values Detail ===\n""")

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
                    outwardIssue: [key: originalIssue['key'] as String]
                ])
                .asObject(Map)
            
            if (linkResponse['status'] != 201) {
                log('ERROR', "Failed to create clone link", [
                    originalKey: originalIssue['key'] as String,
                    newKey: newIssueKey,
                    status: linkResponse['status'],
                    body: linkResponse['body']
                ])
            }
            
            // Add the cloned label to the original issue
            if (!isSubtask) {  // Only add label to parent issues
                List<String> currentLabels = originalIssue['fields']['labels'] as List<String> ?: []
                if (!currentLabels.contains(CLONED_LABEL)) {
                    currentLabels.add(CLONED_LABEL)
                    def labelResponse = put("/rest/api/3/issue/${originalIssue['key']}")
                        .header('Content-Type', 'application/json')
                        .body([
                            fields: [
                                labels: currentLabels
                            ]
                        ])
                        .asObject(Map)
                    
                    if (labelResponse['status'] != 204) {
                        log('WARN', "Failed to add cloned label", [
                            issueKey: originalIssue['key'] as String,
                            status: labelResponse['status'],
                            body: labelResponse['body']
                        ])
                    }
                }
            }

            return [
                'key': newIssueKey,
                'status': 'success',
                'body': response['body']
            ] as Map<String, Object>
        } else {
            log('ERROR', "Failed to create issue", [
                originalKey: originalIssue['key'] as String,
                status: response['status'],
                body: response['body']
            ])
            return [
                'status': 'error',
                'message': "Failed to create issue",
                'response': response['body']
            ] as Map<String, Object>
        }
    } catch (Exception e) {
        log('ERROR', "Exception while creating issue", [
            originalKey: originalIssue['key'] as String,
            isSubtask: isSubtask,
            parentKey: parentKey,
            language: lang,
            error: e.message
        ])
        return [
            'status': 'error',
            'message': e.message,
            'exception': e.class.name
        ] as Map<String, Object>
    }
}

void processIssue(Map<String, Object> currentIssue, String lang) {
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
                        translatedTo = translatedToField.collect { it['value'] as String }
                    } else if (translatedToField instanceof Map) {
                        translatedTo = [translatedToField['value'] as String]
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

            // Print summary after all processing is complete
            printTranslationSummary(
                issueKey,
                issueTypeName,
                newIssueKey,
                ISSUE_TYPE_MAPPING[issueTypeName],
                lang,
                subtaskMappings
            )
        } else {
            log('ERROR', "Failed to create translation issue", [
                sourceKey: issueKey,
                language: lang,
                error: result['message']
            ])
        }
    } else {
        log('INFO', "No translation needed", [
            issueKey: issueKey,
            language: lang
        ])
    }
}


// Main execution
try {
    if (issue) {
        // Get the issue key from the event
        String issueKey = (issue as Map<String, Object>)['key'] as String
        log('INFO', "Starting post function execution", [issueKey: issueKey])
        
        // Fetch fresh issue data from REST API, because the event's `issue` isn't fully compatible
        def response = get("/rest/api/3/issue/${issueKey}").asObject(Map)
        if (response['status'] != 200) {
            log('ERROR', "Failed to fetch issue details", [
                issueKey: issueKey,
                status: response['status'],
                body: response['body']
            ])
            throw new Exception("Failed to fetch issue details: ${response['body']}")
        }
        
        Map<String, Object> currentIssue = response['body'] as Map<String, Object>
        
        ['ar', 'ru'].each { String lang ->
            processIssue(currentIssue, lang)
        }
        
        log('INFO', "Post function execution completed", [issueKey: issueKey])
    } else {
        // Script Console testing mode
        String testIssueKey = 'TCU-56'
        log('INFO', "Starting test execution", [testIssueKey: testIssueKey])
        
        def testResponse = get("/rest/api/3/issue/${testIssueKey}").asObject(Map)
        if (testResponse['status'] == 200) {
            Map<String, Object> testIssue = testResponse['body'] as Map<String, Object>
            
            ['ar', 'ru'].each { String lang ->
                processIssue(testIssue, lang)
            }
            
            log('INFO', "Test execution completed", [testIssueKey: testIssueKey])
        } else {
            log('ERROR', "Failed to fetch test issue", [
                testIssueKey: testIssueKey,
                status: testResponse.status,
                body: testResponse.body
            ])
        }
    }
} catch (Exception e) {
    Map<String, Object> errorDetails = [
        error: e.message,
        stackTrace: e.stackTrace.take(5)*.toString()
    ]
    
    if (issue) {
        Map<String, Object> currentIssue = issue as Map<String, Object>
        errorDetails.put('issueKey', currentIssue.get('key') as String)
    }
    log('ERROR', "Script execution failed", errorDetails)
    throw e
}
