package com.bertramlabs.plugins.karman

import com.bertramlabs.plugins.karman.local.LocalStorageProvider
import grails.plugins.*

class KarmanGrailsPlugin extends Plugin {
    def version         = "3.0.3"
    def grailsVersion   = "7.0.0 > *"
    def title           = "Karman Plugin"
    def author          = "David Estes"
    def authorEmail     = "davydotcom@gmail.com"
    def description     = 'Karman is a standardized / extensible interface plugin for dealing with various cloud services including Local and S3.'
    def documentation   = "http://wondrify.github.io/karman-core"
    def license         = "APACHE"
    def organization    = [name: "Bertram Labs", url: "http://www.bertramlabs.com/"]
    def issueManagement = [ system: "GITHUB", url: "http://github.com/wondrify/karman-core/issues" ]
    def scm             = [ url: "http://github.com/wondrify/karman-core" ]
    def pluginExcludes  = [
    ]
    def developers      = [ [name: 'Brian Wheeler'], [name: 'David Estes'] ]


    def doWithApplicationContext = { applicationContext ->
        def config = grailsApplication.config.getProperty('grails.plugin.karman',Map,[:])

        KarmanConfigHolder.config = config

    }
}
