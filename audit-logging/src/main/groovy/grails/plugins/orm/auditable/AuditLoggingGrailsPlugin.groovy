package grails.plugins.orm.auditable

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
*/

import grails.plugins.orm.auditable.AuditLogEvent
import grails.plugins.orm.auditable.AuditLogListener
import grails.plugins.orm.auditable.AuditLogListenerUtil
import grails.plugins.*
import groovy.transform.*
import org.grails.datastore.mapping.core.*

/**
 * @author Robert Oschwald
 * @author Aaron Long
 * @author Shawn Hartsock
 * @author Graeme Rocher
 *
 * Credit is due to the following other projects,
 * first is Kevin Burke's HibernateEventsGrailsPlugin
 * second is the AuditLogging post by Rob Monie at
 * http://www.hibernate.org/318.html
 *
 * Combined the two sources to create a Grails
 * Audit Logging plugin that will track individual
 * changes to columns.
 *
 * See Documentation:
 * http://grails.org/plugin/audit-logging
 *
 * Changes:
 * Release 0.3   actorKey and username features allow for the logging of
 *               user or userPrincipal for most security systems.
 * Release 0.4   custom serializable implementation for AuditLogEvent so events can happen
 *               inside a webflow context.
 *               tweak application.properties for loading in other grails versions
 *               update to views to show URI in an event
 *               fix missing oldState bug in change event
 * Release 0.4.1 repackaged for Grails 1.1.1 see GRAILSPLUGINS-1181
 * Release 0.5_ALPHA see GRAILSPLUGINS-391
 *               changes to AuditLogEvent domain object uses composite id to simplify logging
 *               changes to AuditLogListener uses new domain model with separate transaction
 *               for logging action to avoid invalidating the main hibernate session.
 * Release 0.5_BETA see GRAILSPLUGINS-391
 *               testing version released generally.
 * Release 0.5     GRAILSPLUGINS-391, GRAILSPLUGINS-1496, GRAILSPLUGINS-1181, GRAILSPLUGINS-1515, GRAILSPLUGINS-1811
 * Release 0.5.1   fixes regression in field logging
 * Release 0.5.2   GRAILSPLUGINS-1887 and GRAILSPLUGINS-1354
 * Release 0.5.3   GRAILSPLUGINS-2135 GRAILSPLUGINS-2060 && an issue with extra JAR files that are somehow getting released as part of the plugin
 * Release 0.5.4   compatibility issues with Grails 1.3.x
 * Release 0.5.5   collections logging, log ids, replacement patterns, property value masking, large fields support, fixes and enhancements
 * Release 0.5.5.1 Fixed the title. No changes in the plugin code.
 * Release 0.5.5.2 Added issueManagement to plugin descriptor for the portal. No changes in the plugin code.
 * Release 0.5.5.3 Added ability to disable audit logging by config.
 * Release 1.0.0 Grails >= 2.0 ORM agnostic implementation, major cleanup and new features
 * Release 1.0.1 closures, nonVerboseDelete property, provide domain identifier to onSave() handler
 * Release 1.0.2 GPAUDITLOGGING-66
 * Release 1.0.3 GPAUDITLOGGING-64 workaround for duplicate log entries written per configured dataSource
 *               GPAUDITLOGGING-63 logFullClassName property
 * Release 1.0.4 GPAUDITLOGGING-69 allow to set uri per domain object
 *               GPAUDITLOGGING-62 Add identifier in handler map
 *               GPAUDITLOGGING-29 support configurable id mapping for AuditLogEvent
 *               GPAUDITLOGGING-70 support configurable datasource name for AuditLogEvent
 *               GPAUDITLOGGING-74 Impossible to log values of zero or false
 *               GPAUDITLOGGING-75 Support automatic (audit) stamping support on entities
 *
 */

class AuditLoggingGrailsPlugin extends Plugin {
    def grailsVersion = '2.0 > *'
    def title = "Audit Logging Plugin"
    def authorEmail = "roos@symentis.com"
    def description = """ Automatically log change events for domain objects.
The Audit Logging plugin additionally adds an instance hook to domain objects that allows you to hang Audit events off of them.
The events include onSave, onChange, and onDelete.
When called, the event handlers have access to oldObj and newObj definitions that will allow you to take action on what has changed.
    """

    def documentation = 'http://grails.org/plugin/audit-logging'
    def license = 'APACHE'
    def organization = [name: "symentis GmbH", url: "http://www.symentis.com/"]
    def developers = [
        [ name: 'Robert Oschwald', email: 'roos@symentis.com' ],
        [ name: 'Elmar Kretzer', email: 'elkr@symentis.com' ],
        [ name: 'Aaron Long', email: 'longwa@gmail.com' ]
    ]
    def issueManagement = [system: 'JIRA', url: 'http://jira.grails.org/browse/GPAUDITLOGGING']
    def scm = [url: 'https://github.com/robertoschwald/grails-audit-logging-plugin']
    def dependsOn = [:]
    def loadAfter = ['core', 'dataSource']

    // Register generic GORM listener
    void doWithApplicationContext() {
        def application = grailsApplication
        def config = application.config
        boolean disabled = config.getProperty("auditLog.disabled", Boolean, false)
        boolean stampEnabled = config.getProperty("auditLog.stampEnabled", Boolean, true)
        boolean stampAlways = config.getProperty("auditLog.stampAlways", Boolean, false)

        String stampCreatedBy = config.getProperty("auditLog.stampCreatedBy", String, "createdBy")
        String stampLastUpdatedBy = config.getProperty("auditLog.stampLastUpdatedBy", String, "lastUpdatedBy")
        boolean verbose = config.getProperty("auditLog.verbose", Boolean, false)
        boolean nonVerboseDelete = config.getProperty("auditLog.nonVerboseDelete", Boolean, false)
        boolean logFullClassName = config.getProperty("auditLog.logFullClassName", Boolean, false)
        boolean transactional = config.getProperty("auditLog.transactional", Boolean, false)
        boolean logIds = config.getProperty("auditLog.logIds", Boolean, false)
        String sessionAttribute = config.getProperty("auditLog.sessionAttribute", String, "")
        String actorKey = config.getProperty("auditLog.actorKey", String, "")
        Integer truncateLength = config.getProperty("auditLog.truncateLength", Integer, determineDefaultTruncateLength() )
        Closure actorClosure = config.getProperty("auditLog.actorClosure", Closure, AuditLogListenerUtil.actorDefaultGetter)
        String propertyMask = config.getProperty("auditLog.propertyMask", String, "**********")


        applicationContext.getBeansOfType(Datastore).each { String key, Datastore datastore ->
            // Don't register the listener if we are disabled
            if (!disabled && !datastore.config.auditLog.disabled) {
                def listener = new AuditLogListener(datastore)
                listener.grailsApplication = application
                listener.stampEnabled = stampEnabled
                listener.stampAlways = stampAlways
                listener.stampCreatedBy = stampCreatedBy
                listener.stampLastUpdatedBy = stampLastUpdatedBy
                listener.verbose = verbose
                listener.nonVerboseDelete = nonVerboseDelete
                listener.logFullClassName = logFullClassName
                listener.transactional = transactional
                listener.sessionAttribute = sessionAttribute
                listener.actorKey = actorKey
                listener.truncateLength = truncateLength
                listener.actorClosure = actorClosure
                listener.defaultIgnoreList = application.config.auditLog.defaultIgnore?.asImmutable() ?: ['version', 'lastUpdated'].asImmutable()
                listener.defaultMaskList = application.config.auditLog.defaultMask?.asImmutable() ?: ['password'].asImmutable()
                listener.propertyMask = propertyMask
                listener.replacementPatterns = application.config.auditLog.replacementPatterns
                listener.logIds = logIds
                applicationContext.addApplicationListener(listener)
            }
        }
    }

    /**
     * The default truncate length is 255 unless we are using the largeValueColumnTypes, then we allow up to the column size
     */
    private Integer determineDefaultTruncateLength() {
        AuditLogEvent.constrainedProperties.oldValue?.maxSize ?: 255
    }
}
