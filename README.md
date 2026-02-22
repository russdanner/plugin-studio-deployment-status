# Studio Deployment Status

This plugin contains CrafterCMS extensions that improve the visibility and reporting of deployment status

## Capabilities include:
### Deployment Processor that pushes deployment status updates to Slack (per target)
#### Example Successful Publish Notification
<img width="716" height="108" alt="image" src="https://github.com/user-attachments/assets/26fc23e9-5ab1-4c50-a026-c87ef16c1034" />

#### Example of Publish Notification When Deployment is Completed With Errors
<img width="695" height="415" alt="image" src="https://github.com/user-attachments/assets/6c5356f8-41ff-46ae-aafc-46e660fcd61b" />


# Installation
Initial instructions (TODO: improve)
1. Ensure deployer Groovy Sandbox is configured to allow execution to the script
1. Place processor in a location that can be reached by the deployer
2. Configure YAML for each target you wish to publish a status for:
Please see the following example:
```
version: 4.1.3.0
target:
  deployStatusSlackHookUrl: https://hooks.slack.com/services/MY/HOOK/FROMSLACK
  deployStatusReplaceMap: CRAFTER_DATA_ROOT/data/repos/sites/somesite/sandbox=
  env: live
  siteName: somesite
  localRepoPath: CRAFTER_DATA_ROOT/data/repos/sites/somesite/sandbox
  search:
    indexIdFormat: '%s-live'
  deployment:
    scheduling:
      enabled: true
    pipeline:
    - processorName: gitDiffProcessor
    - processorName: scriptProcessor
      scriptPath: 'SCRIPT_ROOT/deployment-status-to-slack-processor.groovy'
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
      scriptPath: 'SCRIPT_ROOT/deployment-status-to-slack-processor.groovy'
    - processorName: fileOutputProcessor
      processorLabel: fileOutputProcessor
```
## Noteworthy Configuation
### Placeholders in configuration
- `CRAFTER_DATA_ROOT`: This is a placehodler for the real fully qualified path to the root of your data folder
- `SCRIPT_ROOT`: This is a placeholder for the real fully qualified path to your deployment scripts folder
### Parameters:
- `deployStatusSlackHookUrl`: this is a hook url you get from Slack for a specific channel
- `deployStatusReplaceMap`: This is a comma serpated key=value map of replacements/redaction of values you dont want to push to slack (like root folder paths etc)
### Structure:
Note that the processor appears twice in the processor chain (right after the diff, and right before the fileOutput processors.) This is important. The first invocation begins trapping deployment errors and the second invocation stops trapping them and sends the report to slack.
