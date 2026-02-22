import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import groovy.json.JsonOutput

import java.io.Serializable
import java.util.List
import java.util.concurrent.CopyOnWriteArrayList

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import org.springframework.context.ConfigurableApplicationContext
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.apache.commons.configuration2.Configuration

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.*
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Configuration
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.layout.PatternLayout

import org.apache.logging.log4j.core.config.Property

import org.craftercms.deployer.api.ChangeSet
import static org.craftercms.commons.config.ConfigUtils.getRequiredStringProperty

// get configurations
def slackHookUrl = applicationContext.getEnvironment().getProperty('target.deployStatusSlackHookUrl')
def replaceMapValue = applicationContext.getEnvironment().getProperty('target.deployStatusReplaceMap')
def replaceMap = [:]
if(replaceMapValue != null) {

   replaceMapValue.split(',').collectEntries { pair ->
     def (k, v) = pair.split('=', 2)
     replaceMap.put(k, v)  
   }
}

// get deployment status values
def deploymentStatusService = lookupDeploymmentStatusService(applicationContext, logger)

def siteName = deployment.target.siteName
def targetId = deployment.target.id
def deploymentId = deployment.getParam("latest_commit_id").name()
def deploymentStatus = ""+deployment
def createdCount = originalChangeSet.getCreatedFiles()?.size()
def updatedCount = originalChangeSet.getUpdatedFiles()?.size()
def deletedCount = originalChangeSet.getDeletedFiles()?.size()

def report = deploymentStatusService.lookupReport(siteName, targetId) 

if(!report || !report.logTap) {
   // assume first invocation in the pipeline, install appender on root
   def tap = new Slf4jLog4j2Tap()   
   deploymentStatusService.logStatus(siteName, targetId, deploymentId, "                     ")
   report = deploymentStatusService.lookupReport(siteName, targetId)
   report.logTap = tap
}
else {
   // assume second invocation in the pipeline, close the tap
   report.logTap.close()
   def status = "Success"
   def logMarkdown = ""

   report.logTap.getMessages().each { msg ->
      if(msg.contains("ERROR")
      || msg.contains("Error")
      || msg.contains("EXCEPTION")
      || msg.contains("Caused by")) {
         status = "Completed with Error"

         if(msg.contains("Processor")) {
            // log output by a processor
            def entry = msg.substring(msg.indexOf("Processor -")+12)
            logMarkdown += entry
         } 
         else {
            // usually a wrap of an existing message like an exception 
            logMarkdown += msg
         }

         if(!logMarkdown.endsWith("\n")) {
           // some messages end with a linebreak, others don't. If the last message didn't have one, add its 
           logMarkdown+='\n' 
         }
         
         // now force an additional line break between each entry
         logMarkdown+='\n'

      }
   }

   // Slack only allows 2500 chars of text
   def clamp = { String s, int max -> (s && s.size() > max) ? (s.take(max - 3) + "...") : (s ?: "") }
   logMarkdown  = clamp(logMarkdown, 2470)

   // replace paths or other sensative things
   replaceMap.each { k, v ->
      logger.info("redacting $k with $v")
      logMarkdown = logMarkdown.replaceAll(k, v)
   }
   
   report.logTap = null
   report.sent=true

   def statusIcon = ("Success".equals(status)) ? "✅" : "🚨"
   def payload = null

   if("Success".equals(status)) {
      payload =
      [
         blocks: [
            [ type:"section", text:[type: "mrkdwn",text: "${statusIcon} Site Target `${targetId}`Deployment: ${status}\nCreated: ${createdCount}, Updated: ${updatedCount}, Deleted: ${deletedCount}\nPublish Commit ID: `${deploymentId}`" ]]
         ]
      ]
   }
   else {
      payload =
      [
         blocks: [
            [ type:"section", text:[type: "mrkdwn",text: "${statusIcon} Site Target `${targetId}` Deployment: ${status}\nCreated: ${createdCount}, Updated: ${updatedCount}, Deleted: ${deletedCount}\nPublish Commit ID: `${deploymentId}`" ] ],
            [ type:"section", text:[type:"mrkdwn", text:"```Deployment log (truncated):\n$logMarkdown\n```"] ]
         ]
      ]
   }
   
   // push message to slack via webhook
   postSlackApi("chat.postMessage", payload, logger, slackHookUrl)
}

return originalChangeSet

/* ============================== */
// Support methods and classes
/* ============================== */


def postSlackApi(url, payload, logger, slackHookUrl) {
    def apiUrl = slackHookUrl
    def json = JsonOutput.toJson(payload)
    
    def client = HttpClient.newHttpClient()

   def req = HttpRequest.newBuilder()
      .uri(URI.create(apiUrl))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(json))
      .build()

   def resp = client.send(req, HttpResponse.BodyHandlers.ofString())
   if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
      logger.error("Slack POST failed: HTTP ${resp.statusCode()} body=${resp.body()}")
   }

   def result = resp.body()
   logger.info(""+resp)
   return result
}


def lookupDeploymmentStatusService(applicationContext, logger) {

   def deploymentStatusService = null
   
   try {
      deploymentStatusService = applicationContext.getBean("deploymentStatusService")
      deploymentStatusService.logger = logger
      // logger.info("Grabbing existing deploymentStatusService bean")

   }
   catch(beanNotFound) {
      // logger.info("Creating deploymentStatusService bean")
      def beanFactory = applicationContext.getBeanFactory()
      deploymentStatusService = new DeploymentStatusService()
      deploymentStatusService.logger = logger
      beanFactory.registerSingleton("deploymentStatusService", deploymentStatusService)
   }

   return deploymentStatusService
}

/**
 * Service bean for managing in memory deployment status for each target
 * We store a report for each deployment target in memory for two reasons
 * - Tracking a single status across all processors
 * - Making sure we only send a report for a specific deployment once, regardless how many times the deployer tries
 */
class DeploymentStatusService {
   
   def reports = [:]
   def logger

   def lookupReport(site, targetId) {
      def reportKey = "$site-$targetId"
      def report = reports.get(reportKey)
      return report
   }

   def storeReport(site, targetId, report) {
      def reportKey = "$site-$targetId"
      reports.put(reportKey, report)
      //debugMessage("Deployment Reports in memory: "+reports.size())
   }

   def debugMessage(message) {
      if(logger) {
         logger.info(message)
      }
      else {
         println(message)
      }
   }

   /*
    * At the moment the only messages we can get is from the log
    * This method is only valable in that it helps manage the report
    * objects
    */
   def logStatus(site, targetId, deploymentId, message) {

      def report = lookupReport(site, targetId)

      if(report) {
         if(report.deploymentId == deploymentId) {

            if(report.sent == false) {
               def reportMessageRecord = createMessage()
               reportMessageRecord.message = message
               report.messages.add(reportMessageRecord)   
            }
         }
         else {
            // this is a new deployment, we need a new report
            report = createReport(site, targetId, deploymentId, message)
         }
      }
      else {
         // there is no report on record for this target
         report = createReport(site, targetId, deploymentId, message)
         storeReport(site, targetId, report)
      }
   }

   def createReport(site, targetId, deploymentId, message) {
      def report = [:]
      report.site = site
      report.deploymentId = deploymentId
      report.targetId = targetId
      report.messages = []
      report.sent = false
      report.logTap = null

      // add the first message
      def msg = createMessage()         
      msg.message = message
      report.messages.add(msg)

      return report
   }

   def createMessage() {
      def message = [:]
      message.timestamp = System.currentTimeMillis()
      message.message = ""

      return message
   }
}

class Slf4jLog4j2Tap implements Closeable {

  static class ListAppender extends AbstractAppender {
    final List<String> messages = new CopyOnWriteArrayList<>()

    ListAppender(String name) {
      super(
        name,
        null,
        PatternLayout.newBuilder()
          .withPattern("%d{HH:mm:ss.SSS} %-5p %c - %m%n%throwable")
          .build(),
        true,
        [] as org.apache.logging.log4j.core.config.Property[]
      )
    }

    @Override
    void append(LogEvent event) {
      // Base message (no stack trace)
      String line = String.valueOf(getLayout().toSerializable(event))

      // If there was an exception, record only type + message (no stack)
      Throwable t = event.getThrown()
      
      if (t != null) {
         String tMsg = t.getMessage()
         line = tMsg + "\nEXCEPTION=" + t.getClass().getName() + (tMsg != null ? (": " + tMsg) : "")
         line += "\nCaused by=" + t.getCause()?.toString()
      }

      if(!messages.contains(line)) {
         // don't repeat the same messages
         messages.add(line)
      }
    }
  }

  final LoggerContext ctx
  final Configuration config
  final LoggerConfig root
  final ListAppender appender
  boolean closed = false

  Slf4jLog4j2Tap(String name = "SLF4J_TAP") {
    ctx = (LoggerContext) LogManager.getContext(false)
    config = ctx.getConfiguration()
    root = config.getRootLogger()

    appender = new ListAppender(name)
    appender.start()

    config.addAppender(appender)
    root.addAppender(appender, null, null)

    ctx.updateLoggers()
  }

  List<String> getMessages() { appender.messages }

  @Override
  void close() {
    if (closed) return
    closed = true

    root.removeAppender(appender.getName())
    appender.stop()
    ctx.updateLoggers()
  }
}