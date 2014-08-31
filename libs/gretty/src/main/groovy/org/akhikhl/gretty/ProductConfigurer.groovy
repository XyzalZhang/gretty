/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

import groovy.json.JsonBuilder
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 *
 * @author akhikhl
 */
class ProductConfigurer {

  protected static final Logger log = LoggerFactory.getLogger(ProductConfigurer)

  protected static final String mainClass = 'org.akhikhl.gretty.GrettyStarter'

  protected final Project project
  protected final File baseOutputDir
  protected final String productName
  protected final ProductExtension product
  protected final File outputDir

  protected ServerConfig sconfig
  protected List<WebAppConfig> wconfigs
  protected jsonConfig
  protected String logbackConfig
  protected Map launchScripts = [:]

  ProductConfigurer(Project project, File baseOutputDir, String productName, ProductExtension product) {
    this.project = project
    this.baseOutputDir = baseOutputDir
    this.productName = productName
    this.product = product
    outputDir = new File(baseOutputDir, productName ?: project.name)
  }

  void configureProduct() {

    def buildProductTask = project.task("buildProduct${productName}", group: 'gretty') {

      dependsOn {
        resolveConfig()
        wconfigs.findResults { wconfig ->
          def result = []
          if(wconfig.projectPath) {
            def proj = project.project(wconfig.projectPath)
            result.add(proj.tasks.build)
            if(ProjectUtils.isSpringBootApp(proj, wconfig))
              result.add(proj.tasks.jar)
          }
          return result
        }.flatten()
      }

      inputs.property 'config', {
        resolveConfig()
        jsonConfig.toString()
      }

      inputs.property 'logbackConfig', {
        resolveConfig()
        logbackConfig
      }

      inputs.property 'launchScripts', {
        resolveConfig()
        launchScripts
      }

      inputs.property 'realms', {
        resolveConfig()
        [ '#server': sconfig.realm ] + wconfigs.collectEntries({ [ it.contextPath, it.realm ] })
      }

      inputs.files {
        resolveConfig()
        def result = []
        for(WebAppConfig wconfig in wconfigs) {
          // projects are already set as input in dependsOn
          if(!wconfig.projectPath)
            result.add wconfig.resourceBase
          if(wconfig.realmConfigFile)
            result.add wconfig.realmConfigFile
          if(wconfig.contextConfigFile)
            result.add wconfig.contextConfigFile
          if(wconfig.extraResourceBases) {
            def proj = wconfig.projectPath ? project.project(wconfig.projectPath) : project
            for(def resBase in wconfig.extraResourceBases)
              result.addAll proj.fileTree(resBase).files
          }
          result
        }
        result
      }

      inputs.files {
        resolveConfig()
        def result = []
        if(sconfig.realmConfigFile)
          result.add sconfig.realmConfigFile
        if(sconfig.serverConfigFile)
          result.add sconfig.serverConfigFile
        if(sconfig.logbackConfigFile)
          result.add sconfig.logbackConfigFile
        result
      }

      inputs.files project.configurations.grettyStarter

      inputs.files {
        resolveConfig()
        getRunnerFileCollection()
      }

      outputs.dir outputDir

      doLast {
        resolveConfig()
        writeConfigFiles()
        writeLaunchScripts()
        copyWebappFiles()
        copyStarter()
        copyRunner()
      }
    }

    project.tasks.buildAllProducts.dependsOn buildProductTask

    def archiveProductTask = project.task("archiveProduct${productName}", group: 'gretty') {

      dependsOn buildProductTask

      doLast {
        println "archiving product $productName"
      }
    }

    project.tasks.archiveAllProducts.dependsOn archiveProductTask
  }

  void copyRunner() {

    ManagedDirectory dir = new ManagedDirectory(new File(outputDir, 'runner'))

    for(File file in getRunnerFileCollection().files)
      dir.add(file)

    dir.cleanup()
  }

  void copyStarter() {

    ManagedDirectory dir = new ManagedDirectory(new File(outputDir, 'starter'))

    for(File file in project.configurations.grettyStarter.files)
      dir.add(file)

    dir.cleanup()
  }

  void copyWebappFiles() {

    ManagedDirectory webappsDir = new ManagedDirectory(new File(outputDir, 'webapps'))

    for(WebAppConfig wconfig in wconfigs) {
      String appDir = ProjectUtils.getWebAppDestinationDirName(project, wconfig)
      if(ProjectUtils.isSpringBootApp(project, wconfig)) {
        def files
        if(wconfig.projectPath) {
          def proj = project.project(wconfig.projectPath)
          for(File webappDir in ProjectUtils.getWebAppDirs(proj))
            for(File f in (webappDir.listFiles() ?: []))
              webappsDir.add(f, appDir)
          def resolvedClassPath = new LinkedHashSet<URL>()
          resolvedClassPath.addAll(ProjectUtils.getClassPathJars(proj, 'runtimeNoSpringBoot'))
          resolvedClassPath.addAll(ProjectUtils.resolveClassPath(proj, wconfig.classPath))
          files = resolvedClassPath.collect { new File(it.path) }
          files -= getRunnerFileCollection().files
        } else {
          def file = wconfig.resourceBase
          if(!(file instanceof File))
            file = new File(file.toString())
          files = [ file ]
        }
        for(File file in files) {
          if(file.isDirectory())
            for(File f in (file.listFiles() ?: []))
              webappsDir.add(f, appDir + '/WEB-INF/classes')
          else
            webappsDir.add(file, appDir + '/WEB-INF/lib')
        }
      } else {
        def file = wconfig.resourceBase
        if(!(file instanceof File))
          file = new File(file.toString())
        webappsDir.add(file)
      }      
    }

    webappsDir.cleanup()
    
    if(wconfigs.find { it.extraResourceBases }) {
      ManagedDirectory extraResourcesDir = new ManagedDirectory(new File(outputDir, 'extraResources'))
      for(WebAppConfig wconfig in wconfigs) {
        String appDir = ProjectUtils.getWebAppDestinationDirName(project, wconfig)
        for(def resBase in wconfig.extraResourceBases)
          extraResourcesDir.add(resBase, appDir)
      }
      extraResourcesDir.cleanup()
    }
  }

  protected void createLaunchScripts() {

    String shellResolveDir = '#!/bin/bash\n' +
      'SOURCE="${BASH_SOURCE[0]}"\n' +
      'while [ -h "$SOURCE" ]; do # resolve $SOURCE until the file is no longer a symlink\n' +
      'DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )"\n' +
      'SOURCE="$(readlink "$SOURCE")"\n' +
      '[[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"\n' +
      'done\n' +
      'DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )"\n'

    for(String cmd in ['run', 'start', 'stop', 'restart']) {
      launchScripts[cmd + '.sh'] = shellResolveDir + 'java -Dfile.encoding=UTF8 -cp \"${DIR}/conf/:${DIR}/starter/*\" ' + mainClass + ' ' + cmd + ' $@'
      launchScripts[cmd + '.bat'] = '@java.exe -Dfile.encoding=UTF8 -cp \"%~dp0\\conf\\;%~dp0\\starter\\*\" ' + mainClass + ' ' + cmd + ' %*'
    }
  }

  protected FileCollection getRunnerFileCollection() {
    def servletContainerConfig = ServletContainerConfig.getConfig(sconfig.servletContainer)
    def files
    if(ProjectUtils.anyWebAppUsesSpringBoot(project, wconfigs)) {
      files = project.configurations.grettyNoSpringBoot +
        project.configurations[servletContainerConfig.servletContainerRunnerConfig]
      if(servletContainerConfig.servletContainerType == 'jetty')
        files += project.configurations.grettyRunnerSpringBootJetty
      else if(servletContainerConfig.servletContainerType == 'tomcat')
        files += project.configurations.grettyRunnerSpringBootTomcat
    } else
      files = project.configurations[servletContainerConfig.servletContainerRunnerConfig]
    files
  }

  protected String getSpringBootMainClass() {
    sconfig.springBootMainClass ?:
    SpringBootMainClassFinder.findMainClass(project) ?:
    wconfigs.findResult { it.projectPath ? SpringBootMainClassFinder.findMainClass(project.project(it.projectPath)) : null }
  }

  protected resolveConfig() {
    if(sconfig != null)
      return
    FarmConfigurer configurer = new FarmConfigurer(project)
    FarmExtension productFarm = new FarmExtension()
    configurer.configureFarm(productFarm,
      new FarmExtension(logDir: 'logs'),
      new FarmExtension(serverConfig: product.serverConfig, webAppRefs: product.webAppRefs),
      configurer.findProjectFarm(productName)
    )
    sconfig = productFarm.serverConfig
    wconfigs = []
    configurer.resolveWebAppRefs(productFarm.webAppRefs, wconfigs, false)
    for(WebAppConfig wconfig in wconfigs)
      ProjectUtils.prepareToRun(project, wconfig)
    CertificateGenerator.maybeGenerate(project, sconfig)
    if(!sconfig.logbackConfigFile)
      logbackConfig = LogbackUtils.generateLogbackConfig(sconfig)
    jsonConfig = writeConfigToJson()
    createLaunchScripts()
  }

  protected void writeConfigFiles() {

    // certificate files might be deleted by clean task
    if(sconfig.sslKeyStorePath instanceof File &&
       sconfig.sslKeyStorePath.absolutePath.startsWith(new File(project.buildDir, 'ssl').absolutePath) &&
       !sconfig.sslKeyStorePath.exists()) {
      sconfig.sslKeyStorePath = null
      CertificateGenerator.maybeGenerate(project, sconfig)
      jsonConfig = writeConfigToJson()
    }

    // there are cases when springBootMainClass was accessed too early (in configuration phase),
    // so we neeed to recalculate it.
    if(wconfigs.find { ProjectUtils.isSpringBootApp(project, it) } && jsonConfig.content.springBootMainClass == null) {
      for(WebAppConfig wconfig in wconfigs)
        ProjectUtils.prepareToRun(project, wconfig)
      jsonConfig = writeConfigToJson()
    }

    ManagedDirectory dir = new ManagedDirectory(new File(outputDir, 'conf'))

    File configFile = new File(dir.baseDir, 'server.json')
    configFile.parentFile.mkdirs()
    configFile.text = jsonConfig.toPrettyString()
    dir.registerAdded(configFile)

    if(sconfig.sslKeyStorePath)
      dir.add(sconfig.sslKeyStorePath)

    if(sconfig.sslTrustStorePath)
      dir.add(sconfig.sslTrustStorePath)

    if(sconfig.realmConfigFile)
      dir.add(sconfig.realmConfigFile)

    if(sconfig.serverConfigFile)
      dir.add(sconfig.serverConfigFile)

    if(sconfig.logbackConfigFile)
      dir.add(sconfig.logbackConfigFile)
    else {
      File logbackConfigFile = new File(dir.baseDir, 'logback.groovy')
      logbackConfigFile.parentFile.mkdirs()
      logbackConfigFile.text = logbackConfig
      dir.registerAdded(logbackConfigFile)
    }

    for(WebAppConfig wconfig in wconfigs) {
      String appDir = ProjectUtils.getWebAppDestinationDirName(project, wconfig)
      if(wconfig.realmConfigFile)
        dir.add(wconfig.realmConfigFile, appDir)
      if(wconfig.contextConfigFile)
        dir.add(wconfig.contextConfigFile, appDir)
    }

    dir.cleanup()
  }

  protected writeConfigToJson() {
    def json = new JsonBuilder()
    json {
      writeConfigToJson(delegate)
    }
    json
  }

  protected void writeConfigToJson(json) {
    def self = this
    def getFileName = { file ->
      if(file == null)
        return null
      if(!(file instanceof File))
        file = new File(file.toString())
      file.name
    }
    def servletContainerConfig = ServletContainerConfig.getConfig(sconfig.servletContainer)
    json.with {
      productName self.productName ?: project.name
      servletContainer {
        id sconfig.servletContainer
        version servletContainerConfig.servletContainerVersion
        description servletContainerConfig.servletContainerDescription
      }
      serverConfig {
        if(sconfig.servicePort != ServerConfig.defaultServicePort)
          servicePort sconfig.servicePort
        if(sconfig.statusPort != ServerConfig.defaultStatusPort)
          statusPort sconfig.statusPort
        if(sconfig.host)
          host sconfig.host
        if(sconfig.httpEnabled) {
          if(sconfig.httpPort)
            httpPort sconfig.httpPort
          if(sconfig.httpIdleTimeout)
            httpIdleTimeout sconfig.httpIdleTimeout
        } else
          httpEnabled false
        if(sconfig.httpsEnabled) {
          httpsEnabled true
          if(sconfig.httpsPort)
            httpsPort sconfig.httpsPort
          if(sconfig.httpsIdleTimeout)
            httpsIdleTimeout sconfig.httpsIdleTimeout
          if(sconfig.sslKeyStorePath)
            sslKeyStorePath 'conf/' + sconfig.sslKeyStorePath.name
          if(sconfig.sslKeyStorePassword)
            sslKeyStorePassword sconfig.sslKeyStorePassword
          if(sconfig.sslKeyManagerPassword)
            sslKeyManagerPassword sconfig.sslKeyManagerPassword
          if(sconfig.sslTrustStorePath)
            sslTrustStorePath 'conf/' + sconfig.sslTrustStorePath.name
          if(sconfig.sslTrustStorePassword)
            sslTrustStorePassword sconfig.sslTrustStorePassword
        }
        if(sconfig.realm)
          realm sconfig.realm
        if(sconfig.realmConfigFile)
          realmConfigFile 'conf/' + getFileName(sconfig.realmConfigFile)
        if(sconfig.serverConfigFile)
          serverConfigFile 'conf/' + getFileName(sconfig.serverConfigFile)
        logbackConfigFile 'conf/' + (getFileName(sconfig.logbackConfigFile) ?: 'logback.groovy')
        if(sconfig.secureRandom != null)
          secureRandom sconfig.secureRandom
        if(wconfigs.find { ProjectUtils.isSpringBootApp(project, it) })
          springBootMainClass self.getSpringBootMainClass()
        if(sconfig.singleSignOn != null)
          singleSignOn sconfig.singleSignOn
      }
      webApps wconfigs.collect { WebAppConfig wconfig ->
        { ->
          String webappDestName = ProjectUtils.getWebAppDestinationDirName(project, wconfig)
          String appConfigDir = 'conf/' + webappDestName
          contextPath wconfig.contextPath
          if(ProjectUtils.isSpringBootApp(project, wconfig))
            resourceBase 'webapps/' + ProjectUtils.getWebAppDestinationDirName(project, wconfig)
          else
            resourceBase 'webapps/' + getFileName(wconfig.resourceBase)
          if(wconfig.extraResourceBases)
            extraResourceBases wconfig.extraResourceBases.collect { 'extraResources/' + webappDestName + '/' + getFileName(it) }
          if(wconfig.initParameters)
            initParams wconfig.initParameters
          if(wconfig.realm)
            realm wconfig.realm
          if(wconfig.realmConfigFile)
            realmConfigFile appConfigDir + '/' + getFileName(wconfig.realmConfigFile)
          if(wconfig.contextConfigFile)
            contextConfigFile appConfigDir + '/' + getFileName(wconfig.contextConfigFile)
          if(ProjectUtils.isSpringBootApp(project, wconfig))
            springBoot true
          if(wconfig.springBootSources)
            springBootSources wconfig.springBootSources
        }
      }
    } // json
  }

  protected void writeLaunchScripts() {
    launchScripts.each { scriptName, scriptText ->
      File launchScriptFile = new File(outputDir, scriptName)
      launchScriptFile.text = scriptText
      if(scriptName.endsWith('.sh'))
        launchScriptFile.setExecutable(true)
    }
  }
}
