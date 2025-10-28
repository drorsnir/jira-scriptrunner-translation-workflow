import groovy.transform.Field
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Field static final boolean DEBUG = false  // Set to true for detailed debug output

@Field static final String CLONED_LABEL = 'cloned_for_translation'

// Configuration for error notifications
@Field static final String NOTIFICATION_EMAIL = "admin@example.com" // TODO: Replace with your email
@Field static final String POSTMARK_API_KEY = "" // TODO: Replace with your PostMark API key
@Field static final String POSTMARK_FROM_EMAIL = "admin@example.com" // TODO: Replace with your verified sender email
@Field static final boolean SEND_EMAIL_NOTIFICATIONS = true // Set to false to disable email notifications
@Field static final boolean FAIL_ON_ERRORS = true // Set to false to continue execution despite errors

// Error tracking
@Field static final List<Map<String, Object>> EXECUTION_ERRORS = []

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

// Email notification helper using PostMark API
void sendErrorNotification(String subject, String body, Map<String, Object> errorDetails) {
	if (!SEND_EMAIL_NOTIFICATIONS) {
		log('INFO', "Email notifications disabled, skipping notification for: ${subject}")
		return
	}

	if (!NOTIFICATION_EMAIL || NOTIFICATION_EMAIL == "your-admin-email@company.com") {
		log('WARN', "Email notifications enabled but NOTIFICATION_EMAIL not configured")
		return
	}

	if (!POSTMARK_API_KEY || POSTMARK_API_KEY == "your-postmark-api-key") {
		log('WARN', "Email notifications enabled but POSTMARK_API_KEY not configured")
		return
	}

	try {
		// Format error details nicely for email
		String formattedDetails = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(errorDetails))

		// Build HTML email body
		String htmlBody = """
<html>
<head>
<style>
body { font-family: Arial, sans-serif; line-height: 1.6; }
h2 { color: #d32f2f; }
h3 { color: #555; }
.details { background-color: #f5f5f5; padding: 15px; border-left: 4px solid #d32f2f; margin: 15px 0; }
.info { background-color: #e3f2fd; padding: 10px; border-left: 4px solid #2196f3; margin: 10px 0; }
pre { background-color: #263238; color: #aed581; padding: 10px; overflow-x: auto; }
</style>
</head>
<body>
<h2>Translation Script Error</h2>
<p><strong>Time:</strong> ${LocalDateTime.now()}</p>
<p><strong>Error:</strong> ${body}</p>

<div class="info">
<h3>Script Information</h3>
<ul>
<li><strong>Script:</strong> Translation Listener</li>
<li><strong>Triggered by:</strong> ${errorDetails.originalKey ?: errorDetails.issueKey ?: 'Unknown'}</li>
<li><strong>Language:</strong> ${errorDetails.language ?: 'N/A'}</li>
</ul>
</div>

<div class="details">
<h3>Error Details</h3>
<pre>${formattedDetails}</pre>
</div>

<h3>Action Required</h3>
<p>Please investigate and resolve the error in the ScriptRunner listener.</p>
</body>
</html>
"""

		// Send email via PostMark API
		def emailResponse = post('https://api.postmarkapp.com/email')
			.header('Accept', 'application/json')
			.header('Content-Type', 'application/json')
			.header('X-Postmark-Server-Token', POSTMARK_API_KEY)
			.body([
				From: POSTMARK_FROM_EMAIL,
				To: NOTIFICATION_EMAIL,
				Subject: "Jira ScriptRunner Error: ${subject}",
				HtmlBody: htmlBody,
				TextBody: """Translation Script Error

Time: ${LocalDateTime.now()}
Error: ${body}

Script Information:
- Script: Translation Listener
- Triggered by: ${errorDetails.originalKey ?: errorDetails.issueKey ?: 'Unknown'}
- Language: ${errorDetails.language ?: 'N/A'}

Error Details:
${formattedDetails}

Action Required:
Please investigate and resolve the error in the ScriptRunner listener.
""",
				MessageStream: 'outbound'
			])
			.asObject(Map)

		if (emailResponse['status'] == 200) {
			log('INFO', "Successfully sent error notification email", [
				to: NOTIFICATION_EMAIL,
				subject: subject,
				messageId: emailResponse['body']['MessageID']
			])
		} else {
			log('ERROR', "Failed to send error notification email", [
				status: emailResponse['status'],
				body: emailResponse['body']
			])
		}
	} catch (Exception e) {
		log('ERROR', "Exception while sending error notification email", [
			error: e.message,
			originalSubject: subject,
			stackTrace: e.stackTrace.take(3)*.toString()
		])
	}
}

// Add error to collection
void addError(Map<String, Object> errorInfo) {
	errorInfo['timestamp'] = LocalDateTime.now().toString()
	EXECUTION_ERRORS.add(errorInfo)

	// Log the error
	log('ERROR', "Error added to execution errors", errorInfo)

	// Don't send immediate emails - errors will be sent in summary at end of execution
}

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
        
        addError([
            type: 'FIELD_VALIDATION',
            message: errorMsg,
            issueKey: issue['key'] as String,
            missingFields: missingFields,
            critical: false
        ])
        
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

            addError([
                type: 'SUBTASK_FETCH',
                message: "Failed to fetch subtask details",
                subtaskKey: subtaskKey,
                status: response['status'],
                critical: true  // Critical - can't clone subtask without fetching it
            ])

            return null
        }
    } catch (Exception e) {
        log('ERROR', "Exception while fetching subtask details", [
            subtaskKey: subtaskKey,
            error: e.message
        ])

        addError([
            type: 'SUBTASK_FETCH_EXCEPTION',
            message: "Exception fetching subtask: ${e.message}",
            subtaskKey: subtaskKey,
            exception: e.class.name,
            critical: true  // Critical - can't clone subtask without fetching it
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
        
        addError([
            type: 'NEEDS_TRANSLATION_CHECK',
            message: "Error checking translation need: ${e.message}",
            issueKey: issueKey,
            language: lang,
            exception: e.class.name,
            critical: false
        ])
        
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
        
        addError([
            type: 'FIELD_COPY',
            message: "Error copying fields: ${e.message}",
            originalKey: sourceIssue['key'] as String,
            exception: e.class.name,
            critical: false
        ])
        
        throw e
    }
}

Map<String, Object> createIssue(Map<String, Object> originalIssue, String lang, boolean isSubtask = false, String parentKey = null) {
    String originalKey = originalIssue['key'] as String
    
    try {
        Map<String, Object> fields = copyFields(originalIssue)

        // Set project from the original issue
        String sourceProjectKey = (originalIssue['fields']['project'] as Map<String, Object>)['key'] as String
        fields['project'] = [key: sourceProjectKey]

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
                    outwardIssue: [key: originalKey]
                ])
                .asObject(Map)

            if (linkResponse['status'] != 201) {
                Map<String, Object> linkErrorInfo = [
                    type: 'CLONE_LINK_FAILED',
                    message: "Failed to create clone link between issues",
                    originalKey: originalKey,
                    newKey: newIssueKey,
                    language: lang,
                    isSubtask: isSubtask,
                    status: linkResponse['status'],
                    responseBody: linkResponse['body'],
                    critical: true  // Link creation is critical
                ]

                log('ERROR', "Failed to create clone link", linkErrorInfo)
                addError(linkErrorInfo)
            }
            
            // Add the cloned label to the original issue
            if (!isSubtask) {  // Only add label to parent issues
                List<String> currentLabels = originalIssue['fields']['labels'] as List<String> ?: []
                if (!currentLabels.contains(CLONED_LABEL)) {
                    currentLabels.add(CLONED_LABEL)
                    def labelResponse = put("/rest/api/3/issue/${originalKey}")
                        .header('Content-Type', 'application/json')
                        .body([
                            fields: [
                                labels: currentLabels
                            ]
                        ])
                        .asObject(Map)
                    
                    if (labelResponse['status'] != 204) {
                        log('WARN', "Failed to add cloned label", [
                            issueKey: originalKey,
                            status: labelResponse['status'],
                            body: labelResponse['body']
                        ])
                        // Non-critical error, don't add to EXECUTION_ERRORS
                    }
                }
            }

            return [
                'key': newIssueKey,
                'status': 'success',
                'body': response['body']
            ] as Map<String, Object>
            
        } else {
            // This is a critical error - issue creation failed
            Map<String, Object> errorInfo = [
                type: 'ISSUE_CREATION_FAILED',
                message: "Failed to create issue",
                originalKey: originalKey,
                language: lang,
                isSubtask: isSubtask,
                parentKey: parentKey,
                status: response['status'],
                responseBody: response['body'],
                critical: true
            ]
            
            log('ERROR', "Failed to create issue", errorInfo)
            addError(errorInfo)

            return [
                'status': 'error',
                'message': "Failed to create issue",
                'response': response['body']
            ] as Map<String, Object>
        }
    } catch (Exception e) {
        // Exception during issue creation is critical
        Map<String, Object> errorInfo = [
            type: 'ISSUE_CREATION_EXCEPTION',
            message: "Exception creating issue: ${e.message}",
            originalKey: originalKey,
            isSubtask: isSubtask,
            parentKey: parentKey,
            language: lang,
            exception: e.class.name,
            stackTrace: e.stackTrace.take(5)*.toString(),
            critical: true
        ]
        
        log('ERROR', "Exception while creating issue", errorInfo)
        addError(errorInfo)

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

    try {
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
                                // Error already added in createIssue
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
                // Error already added in createIssue
            }
        } else {
            log('INFO', "No translation needed", [
                issueKey: issueKey,
                language: lang
            ])
        }
    } catch (Exception e) {
        Map<String, Object> errorInfo = [
            type: 'PROCESS_ISSUE_EXCEPTION',
            message: "Exception processing issue: ${e.message}",
            issueKey: issueKey,
            language: lang,
            exception: e.class.name,
            stackTrace: e.stackTrace.take(5)*.toString(),
            critical: true
        ]
        
        log('ERROR', "Exception in processIssue", errorInfo)
        addError(errorInfo)
        
        // Re-throw to be caught by main execution
        throw e
    }
}

// Main execution
try {
    // Clear errors at start of execution
    EXECUTION_ERRORS.clear()

    if (binding.hasVariable('issue') && issue) {
        // Get the issue key from the event
        String issueKey = (issue as Map<String, Object>)['key'] as String
        log('INFO', "Starting post function execution", [issueKey: issueKey])
        
        try {
            // Fetch fresh issue data from REST API, because the event's `issue` isn't fully compatible
            def response = get("/rest/api/3/issue/${issueKey}").asObject(Map)
            if (response['status'] != 200) {
                Map<String, Object> errorInfo = [
                    type: 'ISSUE_FETCH_FAILED',
                    message: "Failed to fetch issue details",
                    issueKey: issueKey,
                    status: response['status'],
                    responseBody: response['body'],
                    critical: true
                ]
                
                log('ERROR', "Failed to fetch issue details", errorInfo)
                addError(errorInfo)
                
                throw new Exception("Failed to fetch issue details: ${response['body']}")
            }
            
            Map<String, Object> currentIssue = response['body'] as Map<String, Object>
            
// Process for each language
            ['ar', 'ru'].each { String lang ->
                try {
                    processIssue(currentIssue, lang)
                } catch (Exception e) {
                    // Error already logged and added in processIssue
                    log('ERROR', "Failed to process issue for language ${lang}", [
                        issueKey: issueKey,
                        language: lang,
                        error: e.message
                    ])
                }
            }
            
        } catch (Exception e) {
            Map<String, Object> errorInfo = [
                type: 'MAIN_EXECUTION_EXCEPTION',
                message: "Exception in main execution: ${e.message}",
                issueKey: issueKey,
                exception: e.class.name,
                stackTrace: e.stackTrace.take(5)*.toString(),
                critical: true
            ]
            
            log('ERROR', "Exception in main execution", errorInfo)
            addError(errorInfo)
        }
        
        // Check if we had any errors during execution
        if (!EXECUTION_ERRORS.isEmpty()) {
            // Prepare error summary
            Map<String, Integer> errorCounts = [:]
            EXECUTION_ERRORS.each { error ->
                String type = error.type as String
                errorCounts[type] = (errorCounts[type] ?: 0) + 1
            }
            
            // Group errors by criticality
            List<Map<String, Object>> criticalErrors = EXECUTION_ERRORS.findAll { it.critical == true }
            List<Map<String, Object>> nonCriticalErrors = EXECUTION_ERRORS.findAll { it.critical != true }
            
            String errorSummary = """
Translation script encountered ${EXECUTION_ERRORS.size()} error(s):
- Critical errors: ${criticalErrors.size()}
- Non-critical errors: ${nonCriticalErrors.size()}

Error types:
${errorCounts.collect { type, count -> "  - ${type}: ${count}" }.join('\n')}

Critical errors:
${criticalErrors.collect { err ->
    "  - [${err.type}] ${err.originalKey ?: err.issueKey ?: 'Unknown'}: ${err.message}"
}.join('\n') ?: '  None'}

Non-critical errors:
${nonCriticalErrors.collect { err ->
    "  - [${err.type}] ${err.originalKey ?: err.issueKey ?: 'Unknown'}: ${err.message}"
}.join('\n') ?: '  None'}
"""

            // Output errors to console/result buffer
            println "\n" + "=" * 80
            println "EXECUTION ERRORS SUMMARY"
            println "=" * 80
            println errorSummary

            // Print detailed error information
            if (!EXECUTION_ERRORS.isEmpty()) {
                println "\n" + "-" * 80
                println "DETAILED ERROR INFORMATION"
                println "-" * 80
                EXECUTION_ERRORS.eachWithIndex { err, idx ->
                    println "\n[${idx + 1}] ${err.type} ${err.critical ? '⚠ CRITICAL' : 'ℹ WARNING'}"
                    println "    Issue: ${err.originalKey ?: err.issueKey ?: 'Unknown'}"
                    println "    Message: ${err.message}"
                    println "    Time: ${err.timestamp}"
                    if (err.language) println "    Language: ${err.language}"
                    if (err.status) println "    HTTP Status: ${err.status}"
                    if (err.responseBody) println "    Response: ${err.responseBody}"
                }
                println "\n" + "=" * 80 + "\n"
            }

            // Send comprehensive error notification with ALL errors
            sendErrorNotification(
                "Script execution completed with ${EXECUTION_ERRORS.size()} errors (${criticalErrors.size()} critical)",
                errorSummary,
                [
                    totalErrors: EXECUTION_ERRORS.size(),
                    criticalCount: criticalErrors.size(),
                    nonCriticalCount: nonCriticalErrors.size(),
                    errorTypes: errorCounts,
                    issueKey: issueKey,
                    executionTime: LocalDateTime.now().toString(),
                    allErrors: EXECUTION_ERRORS // Include ALL errors in detail
                ]
            )
            
            // If FAIL_ON_ERRORS is true and we have critical errors, throw exception to mark execution as failed
            if (FAIL_ON_ERRORS && !criticalErrors.isEmpty()) {
                String failureMessage = "Script execution failed with ${criticalErrors.size()} critical error(s):\n" +
                    criticalErrors.take(3).collect { err ->
                        "- ${err.message}"
                    }.join('\n')
                
                if (criticalErrors.size() > 3) {
                    failureMessage += "\n... and ${criticalErrors.size() - 3} more critical errors"
                }
                
                log('ERROR', "Marking execution as failed due to critical errors", [
                    criticalErrorCount: criticalErrors.size(),
                    issueKey: issueKey
                ])
                
                throw new Exception(failureMessage)
            }
        }
        
        log('INFO', "Post function execution completed", [
            issueKey: issueKey,
            errors: EXECUTION_ERRORS.size(),
            criticalErrors: EXECUTION_ERRORS.count { it.critical == true }
        ])
        
    } else {
        // Script Console testing mode
        String testIssueKey = 'STAG-188'  // Change this to your test issue
        log('INFO', "Starting test execution", [testIssueKey: testIssueKey])
        
        try {
            def testResponse = get("/rest/api/3/issue/${testIssueKey}").asObject(Map)
            if (testResponse['status'] == 200) {
                Map<String, Object> testIssue = testResponse['body'] as Map<String, Object>
                
                ['ar', 'ru'].each { String lang ->
                    try {
                        processIssue(testIssue, lang)
                    } catch (Exception e) {
                        log('ERROR', "Failed to process test issue for language ${lang}", [
                            testIssueKey: testIssueKey,
                            language: lang,
                            error: e.message
                        ])
                    }
                }
                
                // Check for errors in test mode
                if (!EXECUTION_ERRORS.isEmpty()) {
                    List<Map<String, Object>> criticalErrors = EXECUTION_ERRORS.findAll { it.critical == true }
                    List<Map<String, Object>> nonCriticalErrors = EXECUTION_ERRORS.findAll { it.critical != true }

                    println "\n" + "=" * 80
                    println "TEST EXECUTION ERRORS SUMMARY"
                    println "=" * 80
                    println "Total errors: ${EXECUTION_ERRORS.size()}"
                    println "- Critical errors: ${criticalErrors.size()}"
                    println "- Non-critical errors: ${nonCriticalErrors.size()}"

                    println "\n" + "-" * 80
                    println "DETAILED ERROR INFORMATION"
                    println "-" * 80

                    EXECUTION_ERRORS.eachWithIndex { error, idx ->
                        println "\n[${idx + 1}] ${error.type} ${error.critical ? '⚠ CRITICAL' : 'ℹ WARNING'}"
                        println "    Issue: ${error.originalKey ?: error.issueKey ?: 'Unknown'}"
                        println "    Message: ${error.message}"
                        println "    Time: ${error.timestamp}"
                        if (error.language) println "    Language: ${error.language}"
                        if (error.status) println "    HTTP Status: ${error.status}"
                        if (error.exception) println "    Exception: ${error.exception}"
                        if (error.responseBody) println "    Response: ${error.responseBody}"
                    }
                    println "\n" + "=" * 80 + "\n"

                    if (FAIL_ON_ERRORS && !criticalErrors.isEmpty()) {
                        throw new Exception("Test execution failed with ${criticalErrors.size()} critical errors")
                    }
                }
                
                log('INFO', "Test execution completed", [
                    testIssueKey: testIssueKey,
                    errors: EXECUTION_ERRORS.size()
                ])
            } else {
                log('ERROR', "Failed to fetch test issue", [
                    testIssueKey: testIssueKey,
                    status: testResponse.status,
                    body: testResponse.body
                ])
                throw new Exception("Failed to fetch test issue: ${testResponse.body}")
            }
        } catch (Exception e) {
            log('ERROR', "Test execution failed", [
                testIssueKey: testIssueKey,
                error: e.message,
                stackTrace: e.stackTrace.take(5)*.toString()
            ])
            
            // Send notification for test failures with all errors
            String testErrorSummary = """Test execution failed for ${testIssueKey}

Exception: ${e.message}
Total errors collected: ${EXECUTION_ERRORS.size()}

Errors encountered:
${EXECUTION_ERRORS.collect { err ->
    "- [${err.type}] ${err.critical ? 'CRITICAL' : 'WARNING'} - ${err.originalKey ?: err.issueKey ?: 'Unknown'}: ${err.message}"
}.join('\n')}"""

            sendErrorNotification(
                "Test execution failed for ${testIssueKey}",
                testErrorSummary,
                [
                    testIssueKey: testIssueKey,
                    exception: e.class.name,
                    message: e.message,
                    totalErrors: EXECUTION_ERRORS.size(),
                    allErrors: EXECUTION_ERRORS
                ]
            )
            
            throw e
        }
    }
} catch (Exception e) {
    // This is the final catch - any exception here will mark the execution as failed
    Map<String, Object> errorDetails = [
        error: e.message,
        exception: e.class.name,
        stackTrace: e.stackTrace.take(10)*.toString(),
        executionErrors: EXECUTION_ERRORS.size(),
        timestamp: LocalDateTime.now().toString()
    ]

    if (binding.hasVariable('issue') && issue) {
        Map<String, Object> currentIssue = issue as Map<String, Object>
        errorDetails.put('issueKey', currentIssue.get('key') as String)
    }
    
    log('ERROR', "Script execution failed with unhandled exception", errorDetails)

    // Output fatal error to console
    println "\n" + "=" * 80
    println "FATAL ERROR - SCRIPT EXECUTION FAILED"
    println "=" * 80
    println "Exception: ${e.message}"
    println "Exception Type: ${e.class.name}"
    println "Total errors collected: ${EXECUTION_ERRORS.size()}"

    if (!EXECUTION_ERRORS.isEmpty()) {
        println "\n" + "-" * 80
        println "ALL ERRORS ENCOUNTERED BEFORE FATAL EXCEPTION"
        println "-" * 80
        EXECUTION_ERRORS.eachWithIndex { err, idx ->
            println "\n[${idx + 1}] ${err.type} ${err.critical ? '⚠ CRITICAL' : 'ℹ WARNING'}"
            println "    Issue: ${err.originalKey ?: err.issueKey ?: 'Unknown'}"
            println "    Message: ${err.message}"
            println "    Time: ${err.timestamp}"
            if (err.language) println "    Language: ${err.language}"
            if (err.status) println "    HTTP Status: ${err.status}"
        }
    }

    println "\n" + "-" * 80
    println "STACK TRACE (Top 10)"
    println "-" * 80
    e.stackTrace.take(10).each { println "  ${it}" }
    println "\n" + "=" * 80 + "\n"

    // Build comprehensive error summary for email
    String finalErrorSummary = """Script execution failed with unhandled exception

Exception: ${e.message}
Exception Type: ${e.class.name}
Total errors collected: ${EXECUTION_ERRORS.size()}

${EXECUTION_ERRORS.isEmpty() ? 'No errors were collected before the fatal exception.' : """
All errors encountered during execution:
${EXECUTION_ERRORS.collect { err ->
    """
[${err.type}] ${err.critical ? 'CRITICAL' : 'WARNING'}
  Issue: ${err.originalKey ?: err.issueKey ?: 'Unknown'}
  Message: ${err.message}
  Time: ${err.timestamp}
  ${err.language ? "Language: ${err.language}" : ''}
  ${err.status ? "HTTP Status: ${err.status}" : ''}
""".trim()
}.join('\n\n')}
"""}

Stack trace (top 10):
${e.stackTrace.take(10).collect { "  ${it}" }.join('\n')}"""

    // Send final failure notification with all error details
    sendErrorNotification(
        "CRITICAL: Script execution failed completely",
        finalErrorSummary,
        [
            error: e.message,
            exception: e.class.name,
            totalErrors: EXECUTION_ERRORS.size(),
            allErrors: EXECUTION_ERRORS,
            timestamp: LocalDateTime.now().toString(),
            stackTrace: e.stackTrace.take(10)*.toString()
        ] + (binding.hasVariable('issue') && issue ? [issueKey: (issue as Map<String, Object>)['key']] : [:])
    )
    
    // Re-throw to ensure ScriptRunner marks this as failed
    throw e
} finally {
    // Log execution summary
    if (!EXECUTION_ERRORS.isEmpty()) {
        log('INFO', "Execution completed with errors", [
            totalErrors: EXECUTION_ERRORS.size(),
            criticalErrors: EXECUTION_ERRORS.count { it.critical == true },
            nonCriticalErrors: EXECUTION_ERRORS.count { it.critical != true }
        ])
    }
}
