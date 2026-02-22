# Studio Deployment Status

This plugin contains CrafterCMS extensions that improve the visibility and reporting of deployment status

Capabilities include:
- Deployment Processor that pushes deployment status updates to Slack (per target)
  
# Installation
Initial instructions (TODO: improve)
1. place processor in a location that can be reached by the deployer
2. Configure YAML for target:
Please see the following example:
```
version: 4.1.3.0
target:
  deployStatusSlackHookUrl: https://hooks.slack.com/services/MY/HOOK/FROMSLACK
  deployStatusReplaceMap: CRAFTER_DATA_ROOT/data/repos/sites/hello/sandbox/data/repos/sites/hello/sandbox=
  env: live
  siteName: somesite
  localRepoPath: CRAFTER_DATA_ROOT/data/repos/sites/hello/sandbox
  search:
    indexIdFormat: '%s-live'
  deployment:
    scheduling:
      enabled: true
    pipeline:
    - processorName: gitDiffProcessor
    - processorName: scriptProcessor
      scriptPath: 'SCRIPT_ROOT/deployment-status-processor.groovy'
    - processorName: searchIndexingProcessor
      excludeFiles:
      - ^/sources/.*$
    - processorName: httpMethodCallProcessor
      method: GET
      url: ${target.engineUrl}/api/1/site/cache/clear.json?crafterSite=${target.siteName}&token=${target.engineManagementToken}
    - processorName: httpMethodCallProcessor
      includeFiles:
      - ^/?config/studio/content-types.*$
      method: GET
      url: ${target.engineUrl}/api/1/site/context/graphql/rebuild.json?crafterSite=${target.siteName}&token=${target.engineManagementToken}
    - processorName: scriptProcessor
      scriptPath: 'SCRIPT_ROOT/deployment-status-processor.groovy'
    - processorName: fileOutputProcessor
      processorLabel: fileOutputProcessor
```
## Notworthy configuation:
### Parameters:
- `deployStatusSlackHookUrl`: this is a hook url you get from Slack for a specific channel
- `deployStatusReplaceMap`: This is a comma serpated key=value map of replacements/redaction of values you dont want to push to slack (like root folder paths etc)
 ### Structure:
 Note that the processor appears twice in the processor chain (right after the diff, and right before the fileOutput processors.) This is important. The first invocation begins trapping deployment errors and the second invocation stops trapping them and sends the report to slack.
