/**
 * Copyright (C) 2013 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fr

import scala.util.control.NonFatal
import org.orbeon.oxf.common.OXFException
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.XML._
import org.orbeon.oxf.util.{NetUtils, ScalaUtils}
import collection.JavaConverters._

trait FormRunnerPermissions {

    import FormRunner._

    val PropertyPrefix = "oxf.fr.authentication."

    val MethodPropertyName                  = PropertyPrefix + "method"
    val ContainerRolesPropertyName          = PropertyPrefix + "container.roles" // NOTE: this could be inferred from form-builder-permissions.xml, right?
    val HeaderUsernamePropertyName          = PropertyPrefix + "header.username"
    val HeaderRolesPropertyName             = PropertyPrefix + "header.roles"
    val HeaderGroupPropertyName             = PropertyPrefix + "header.group"
    val HeaderRolesPropertyNamePropertyName = PropertyPrefix + "header.roles.property-name"

    val NameValueMatch = "([^=]+)=([^=]+)".r

    type UserRoles = {
        def getRemoteUser(): String
        def isUserInRole(role: String): Boolean
    }

    /**
     * Get the username and roles from the request, based on the Form Runner configuration.
     */
    def getUserGroupRoles(userRoles: UserRoles, getHeader: String ⇒ Option[Array[String]]): (Option[String], Option[String], Option[Array[String]]) = {

        val propertySet = properties
        propertySet.getString(MethodPropertyName, "container") match {
            case "container" ⇒

                val username    = Option(userRoles.getRemoteUser)
                val rolesString = propertySet.getString(ContainerRolesPropertyName)

                if (rolesString eq null) {
                    (username, None, None)
                } else {

                    // Wrap exceptions as Liferay throws if the role is not available instead of returning false
                    def isUserInRole(role: String) =
                        try userRoles.isUserInRole(role)
                        catch { case NonFatal(_) ⇒ false}

                    val rolesArray =
                        for {
                            role ← rolesString.split(""",|\s+""")
                            if isUserInRole(role)
                        } yield
                            role

                    val roles = rolesArray match {
                        case Array() ⇒ None
                        case array   ⇒ Some(array)
                    }

                    (username, rolesArray.headOption, roles)
                }

            case "header" ⇒

                val headerPropertyName = propertySet.getString(HeaderRolesPropertyNamePropertyName, "").trim match {
                    case "" ⇒ None
                    case value ⇒ Some(value)
                }

                def headerOption(name: String) = Option(propertySet.getString(name)) flatMap (p ⇒ getHeader(p.toLowerCase))

                // Headers can be separated by comma or pipe
                def split1(value: String) = value split """(\s*[,\|]\s*)+"""
                // Then, if configured, a header can have the form name=value, where name is specified in a property
                def split2(value: String) = headerPropertyName match {
                    case Some(propertyName) ⇒
                        value match {
                            case NameValueMatch(`propertyName`, value) ⇒ Seq(value)
                            case _ ⇒ Seq()
                        }
                    case _ ⇒ Seq(value)
                }

                val username = headerOption(HeaderUsernamePropertyName) map (_.head)
                val group    = headerOption(HeaderGroupPropertyName) map (_.head)
                val roles    = headerOption(HeaderRolesPropertyName) map (_ flatMap split1 flatMap (split2(_)))

                (username, group, roles)

            case other ⇒ throw new OXFException("Unsupported authentication method, check the '" + MethodPropertyName + "' property:" + other)
        }
    }

    def getUserRolesAsHeaders(userRoles: UserRoles, getHeader: String ⇒ Option[Array[String]]): Map[String, Array[String]] = {

        val (username, group, roles) = getUserGroupRoles(userRoles, getHeader)
        Seq(
            username map ("orbeon-username" → Array(_)),
            group    map ("orbeon-group"    → Array(_)),
            roles    map ("orbeon-roles"    → _)
        ).flatten.toMap
    }

    /**
     * Given a permission element, e.g. <permission operations="read update delete">, returns the tokenized value of
     * the operations attribute.
     */
    private def permissionOperations(permission: NodeInfo): Seq[String] = (permission \@ "operations").stringValue.split("\\s+")

    /**
     * Given the metadata for a form, returns the sequence of operations that the current user is authorized to perform,
     * just based on the user's roles. Users might be able to perform additional operations on specific data, which
     * can be tested with allAuthorizedOperations().
     * The sequence can contain just the "*" string to denote that the user is allowed to perform any operation.
     */
    def authorizedOperationsBasedOnRoles(permissionsElement: NodeInfo): Seq[String] = {
        if (permissionsElement eq null)
            Seq("*")                                                         // No permissions defined for this form, authorize any operation
        else
            (permissionsElement \ "permission")
                .filter(p ⇒
                    (p \ * isEmpty) ||                                       // Only consider permissions with no constraints (unnecessary line for clarity)
                    (p \ * forall (localname(_) == "user-role")))            // … or with only `user-role` constraints
                .filter(p ⇒
                    p \ "user-role" forall (r ⇒                              // If we have user-role constraints, they must all pass
                        ScalaUtils.split((r \@ "any-of").stringValue)        // Constraint is satisfied if user has at least one of the roles
                        .map(_.replace("%20", " "))                          // Unescape internal spaces as the roles used in Liferay are user-facing labels that can contain space (see also permissions.xbl)
                        .intersect(orbeonRoles.toSeq).nonEmpty))
                .flatMap(permissionOperations)                               // For the permissions that passed, return the list operations
                .distinct                                                    // Remove duplicate operations
    }

    def javaAuthorizedOperationsBasedOnRoles(permissionsElement: NodeInfo): java.util.List[String] =
        authorizedOperationsBasedOnRoles(permissionsElement).asJava

    def allAuthorizedOperations(permissionsElement: NodeInfo, dataUsername: String, dataGroupname: String): Seq[String] = {

        def operations(header: String, dataUserInfo: String, condition: String): Seq[String] = {
            val request = NetUtils.getExternalContext.getRequest
            val headerValue = request.getHeaderValuesMap.get("orbeon-username").headOption
            headerValue
                // Does the user info from the header match the user info on the data
                .filter(_ == dataUserInfo)
                // If it does, return the operation the owner or group-member can perform
                .map(_ ⇒ (permissionsElement \ "permission")
                    .filter(p ⇒ p \ * forall (localname(_) == condition))
                    .flatMap(permissionOperations))
                .getOrElse(Seq.empty)
        }

        val rolesOperations = authorizedOperationsBasedOnRoles(permissionsElement)
        rolesOperations match {
            case Seq("*") ⇒ rolesOperations
            case _ ⇒
                val dataOperations =
                    Seq(("orbeon-username", dataUsername,  "owner"),
                        ("orbeon-group",    dataGroupname, "group-member"))
                        .flatMap(Function.tupled(operations _)(_))
                (rolesOperations ++ dataOperations).distinct
        }
    }

    def javaAllAuthorizedOperations(permissionsElement: NodeInfo, dataUsername: String, dataGroupname: String): java.util.List[String] =
        allAuthorizedOperations(permissionsElement, dataUsername, dataGroupname).asJava

    def setAllAuthorizedOperationsHeader(permissionsElement: NodeInfo, dataUsername: String, dataGroupname: String): Unit = {
        val operations = allAuthorizedOperations(permissionsElement, dataUsername, dataGroupname)
        val response = NetUtils.getExternalContext.getResponse
        response.setHeader("Orbeon-Operations", operations.mkString(" "))
    }

    def orbeonRoles: Set[String] = {
        val request = NetUtils.getExternalContext.getRequest
        Option(request.getHeaderValuesMap.get("orbeon-roles")) getOrElse Array[String]() toSet
    }

    def getFormBuilderPermissionsAsXML(formRunnerRoles: NodeInfo): NodeInfo = {
        // Whether in container or header mode, roles are parsed into the Orbeon-Roles header at this point
        getFormBuilderPermissionsAsXML(formRunnerRoles, orbeonRoles)
    }

    def getFormBuilderPermissionsAsXML(formRunnerRoles: NodeInfo, incomingRoleNames: Set[String]): NodeInfo = {

        val appForms = getFormBuilderPermissions(formRunnerRoles, incomingRoleNames)

        if (appForms.isEmpty)
            <apps has-roles="false" all-roles=""/>
        else
            // Result document contains a tree structure of apps and forms
            <apps has-roles="true" all-roles={incomingRoleNames mkString " "}>{
                appForms map { case (app, forms) ⇒
                    <app name={app}>{ forms map { form ⇒ <form name={form}/> } }</app>
                }
            }</apps>
    }

    def getFormBuilderPermissions(formRunnerRoles: NodeInfo, incomingRoleNames: Set[String]): Map[String, Set[String]] = {

        val configuredRoles = formRunnerRoles.root \ * \ "role"
        if (configuredRoles.isEmpty) {
            // No role configured
            Map()
        } else {
            // Roles configured
            val allConfiguredRoleNames = configuredRoles map (_.attValue("name")) toSet
            val applicableRoleNames = allConfiguredRoleNames & incomingRoleNames
            val applicableRoles = configuredRoles filter (e ⇒ (applicableRoleNames + "*")(e.attValue("name")))
            val applicableAppNames = applicableRoles map (_.attValue("app")) toSet

            if (applicableAppNames("*")) {
                // User has access to all apps (and therefore all forms)
                Map("*" → Set("*"))
            } else {
                // User has access to certain apps only
                (for {
                    app ← applicableAppNames
                    forms = {
                        val applicableFormsForApp = applicableRoles filter (_.attValue("app") == app) map (_.attValue("form")) toSet

                        if (applicableFormsForApp("*")) Set("*") else applicableFormsForApp
                    }
                } yield
                    app → forms) toMap
            }
        }
    }

    private def isAuthorized(appForms: Map[String, Set[String]], app: String, form: String) = {
        // Authorized if access to all apps OR if access to current app AND (access to all forms in app OR to specific form in app)
        (appForms contains "*") || (appForms.get(app) exists (_ & Set("*", form) nonEmpty))
    }
}
