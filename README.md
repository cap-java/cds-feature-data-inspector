[![REUSE status](https://api.reuse.software/badge/github.com/cap-java/cds-feature-data-inspector)](https://api.reuse.software/info/github.com/cap-java/cds-feature-data-inspector)

# SAP Cloud Application Programming Model Data Inspector Plugin for Java

## About this Project

`cds-feature-data-inspector` is a plugin to **view data** of CAP Java applications.

This plugin focuses on the CAP Java runtime: it provides the Data Inspector OData **service definition and handler implementations**. The SAPUI5 app and the `cds add data-inspector` command are provided by the Node.js [`@cap-js/data-inspector`](https://www.npmjs.com/package/@cap-js/data-inspector) plugin. A CAP Java consumer therefore gets the full experience by combining the two: this CAP Java plugin (added as a Maven dependency) for the runtime, and `@cap-js/data-inspector` (as a **devDependency**) for the UI via `cds build` and the project wiring via `cds add`. See [Setup for BTP](#setup-for-btp) and [Accessing the UI Locally](#accessing-the-ui-locally).

## Quick Start

### Installation

Add the plugin to your CAP Java project's `srv/pom.xml`:

```xml
<dependency>
    <groupId>com.sap.cds</groupId>
    <artifactId>cds-feature-data-inspector</artifactId>
    <version>1.0.0</version>
</dependency>
```

Reference the plugin's CDS model with a `using` statement in one of your `srv/*.cds` files (opt-in — the plugin is not activated until you do this):

```cds
using from 'com.sap.cds/cds-feature-data-inspector';
```

To use the SAPUI5 app or `cds add data-inspector`, additionally install the Node.js [`@cap-js/data-inspector`](https://github.com/cap-js/data-inspector) plugin as a devDependency in your host app's `package.json`:

```sh
npm install --save-dev @cap-js/data-inspector
```

Then disable the Node.js plugin's CDS model contribution by adding `"@cap-js/data-inspector": false` under `cds.requires` in your host app's `.cdsrc.json` (or the `cds` block in `package.json`):

```json
{
  "requires": {
    "@cap-js/data-inspector": false
  }
}
```

### Run

Run it with

```sh
mvn spring-boot:run
```

- The Data Inspector OData service is served at `http://localhost:8080/odata/v4/data-inspector/`.
- When asked for a user, use the credentials of a mock user that has the `capDataInspectorReadonly` role (e.g. `alice` with password `alice`, depending on your mock-user configuration). See [Authorization](#authorization).

> **Note:** Mock users are for local testing only. For production deployments, always configure proper authentication via XSUAA. See [Authorization](#authorization).

The SAPUI5 app runs as its own app (served from the HTML5 Application Repository on BTP, or via `ui5 serve` locally), talking to this OData service — see [Accessing the UI Locally](#accessing-the-ui-locally).

## Features

- Exclude specific entities and elements from being exposed by the plugin. See [Excluding Entities and Elements](#excluding-entities-and-elements).
- Automatically log access to sensitive personal data using CAP Java's audit logging. See [Audit Logging](#audit-logging).
- Provide `xsuaa` scope for access control. See [Authorization](#authorization).

> **Note:** The SAPUI5 app is identical to the one shipped by the Node.js plugin. For a tour of the UI, see [The UI at a Glance](https://github.com/cap-js/data-inspector#the-ui-at-a-glance) in the `@cap-js/data-inspector` README.

### Accessing the UI Locally

The SAPUI5 app runs as a standalone UI5 app that talks to this plugin's OData service. It is produced by `cds build` from the Node.js [`@cap-js/data-inspector`](https://github.com/cap-js/data-inspector) plugin (installed as a devDependency, see [Installation](#installation)), which generates a ready-to-run copy in your project under `gen/cap-data-inspector-ui`, pre-configured to talk to your running CAP Java server. Run the UI from that generated folder:

1. **Ensure your CAP Java server is started** (in one terminal), so the OData service is available at `http://localhost:8080`:

   ```sh
   mvn spring-boot:run
   ```

2. **Produce the UI** with the CDS build (in another terminal). This copies and configures the UI5 app into `gen/cap-data-inspector-ui`:

   ```sh
   cds build
   ```

3. **Run the UI** from the generated folder. This starts `ui5 serve`, opens the app in a browser, and proxies OData requests to your CAP Java server (configured in the generated `ui5.yaml`):

   ```sh
   cd gen/cap-data-inspector-ui
   npm install
   npm start
   ```

> **Note:** The dev proxy in the generated `ui5.yaml` targets the CAP Java server at `http://localhost:8080`. If your server runs on a different port, adjust the proxy target in `gen/cap-data-inspector-ui/ui5.yaml`.

> **Note:** Re-run `cds build` whenever you change the plugin configuration so the regenerated `gen/cap-data-inspector-ui` picks up your changes.

### Excluding Entities and Elements

To hide entities or elements from the Data Inspector, annotate them with `@HideFromDataInspector` in your CDS definitions.

**Example**

Using `@HideFromDataInspector` annotation in the CDS entity definitions:

```cds
entity Foo {
    id   : String;
    name : String @HideFromDataInspector;
}
```

The `name` element of the `Foo` entity is not revealed by the Data Inspector.

```cds
@HideFromDataInspector
entity Bar {
    id   : String;
    name : String;
}
```

The `Bar` entity is not revealed by the Data Inspector.

## Setup for BTP

### Prerequisites

1. Add the `cds-feature-data-inspector` Maven dependency to your CAP Java project (see [Installation](#installation)).
2. Install the Node.js [`@cap-js/data-inspector`](https://github.com/cap-js/data-inspector) plugin as a devDependency to produce the SAPUI5 app and to use `cds add data-inspector`:

   ```sh
   npm install --save-dev @cap-js/data-inspector
   ```

   Then add `"@cap-js/data-inspector": false` under `cds.requires` in your `.cdsrc.json` (or `package.json`) to avoid a duplicate `DataInspectorService` definition — see [Installation](#installation) for details.

3. Set up the `xsuaa` SAP BTP service for authorization.
4. Optionally, use CAP Java's [audit logging](https://cap.cloud.sap/docs/java/auditlog) and the `auditlog` SAP BTP service for audit logging.

### Setup with `cds add data-inspector`

`cds add data-inspector` is provided by the Node.js [`@cap-js/data-inspector`](https://github.com/cap-js/data-inspector) plugin and automatically adds the required configuration (XSUAA, MTA, Cloud Portal) to your project. For the full list of changes it applies, see [Setup with `cds add data-inspector`](https://github.com/cap-js/data-inspector#setup-with-cds-add-data-inspector) in the `@cap-js/data-inspector` README.

> **Note:** Running `cds add data-inspector` is optional. To add the required configuration manually, refer to the relevant sections in the `@cap-js/data-inspector` README.

### Authorization

Define and use the `xsuaa` scope `capDataInspectorReadonly` in your `xs-security.json` file to grant read access to the plugin's SAPUI5 app and the underlying OData service.

For local development with mock authentication, grant the `capDataInspectorReadonly` role to a mock user:

```yaml
cds:
  security:
    mock:
      enabled: true
      users:
        alice:
          password: alice
          roles:
            - capDataInspectorReadonly
```

> **Note:** The Data Inspector reads data only through the available CDS services, exposing data based on `xsuaa` scopes granted to the entities and the user. It doesn't implement own access control.

For more on authorization, see [Authorization](https://github.com/cap-js/data-inspector#authorization) in the `@cap-js/data-inspector` README.

### Audit Logging

If your CAP Java application uses [CAP Java's audit logging](https://cap.cloud.sap/docs/java/auditlog), the Data Inspector automatically emits audit logs for read access to sensitive data elements annotated with `@PersonalData.IsPotentiallySensitive`. For audit logging in SAP Cloud Application Programming Model, refer to the [Capire documentation](https://cap.cloud.sap/docs/guides/data-privacy/annotations).

### UI Configuration for SAP BTP

The SAPUI5 app, the `cds build` plugin that produces and patches it, and all of the deployment configuration (MTA, HTML5 Application Repository, Cloud Portal, SAP Build Work Zone) are provided by the Node.js [`@cap-js/data-inspector`](https://github.com/cap-js/data-inspector) plugin. The resulting `gen/cap-data-inspector-ui` folder is the single source of truth for deployment, whether you use MTA-based deployment or `@sap/html5-app-deployer`.

Refer to the following sections of the `@cap-js/data-inspector` README:

- [CDS Build Plugin](https://github.com/cap-js/data-inspector#cds-build-plugin) — including [OData Service Base Path](https://github.com/cap-js/data-inspector#odata-service-base-path), [Custom Destination Name](https://github.com/cap-js/data-inspector#custom-destination-name), [sap.cloud.service Configuration](https://github.com/cap-js/data-inspector#sapcloudservice-configuration), [Authentication Type](https://github.com/cap-js/data-inspector#authentication-type), and [Local Server URL (ui5 serve proxy)](https://github.com/cap-js/data-inspector#local-server-url-ui5-serve-proxy).
- [MTA Deployment](https://github.com/cap-js/data-inspector#mta-deployment)
- [@sap/html5-app-deployer](https://github.com/cap-js/data-inspector#saphtml5-app-deployer)
- [Cloud Portal Service Configuration](https://github.com/cap-js/data-inspector#cloud-portal-service-configuration)
- [SAP Build Work Zone Configuration](https://github.com/cap-js/data-inspector#sap-build-work-zone-configuration)

> **Note (Java-specific):** The `cds build` plugin detects a CAP Java host and resolves the OData service base path from the host's `cds.odata-v4.endpoint.path` (default `/odata/v4`). The generated dev proxy (`ui5.yaml`) targets the CAP Java server at `http://localhost:8080`.

## Support, Feedback, and Contribution

This project is open to feature requests, suggestions, and bug reports through [GitHub issues](https://github.com/cap-java/cds-feature-data-inspector/issues). We encourage and welcome contribution and feedback. For more information about how to contribute, the project structure, and additional contribution information, see the [Contribution Guidelines](CONTRIBUTING.md).

### Development Setup

To quickly test and experience the plugin directly without a dependent project on your local machine, use the included Maven `test-app` module in this repository.

1. Clone the repository:

   ```sh
   git clone https://github.com/cap-java/cds-feature-data-inspector.git
   cd cds-feature-data-inspector
   ```

2. Build the plugin and the test-app (this also installs the local CDS-DK toolchain and deploys the SQLite database):

   ```sh
   mvn clean install -DskipTests
   ```

3. **Terminal 1 — run the OData service:**

   ```sh
   mvn -pl test-app spring-boot:run
   ```

   The Data Inspector OData service is served at `http://localhost:8080/odata/v4/data-inspector/`.

4. **Terminal 2 — build and serve the SAPUI5 app** (it runs as its own UI5 app against the service from Terminal 1):

   ```sh
   cd test-app
   npx cds build          # generates and patches the UI into gen/cap-data-inspector-ui
   cd gen/cap-data-inspector-ui
   npm install
   npm start              # starts ui5 serve, opens the app, proxies OData to localhost:8080
   ```

   > **Note:** `npx cds build` uses the Node.js [`@cap-js/data-inspector`](https://github.com/cap-js/data-inspector) devDependency already declared in `test-app/package.json`. The generated dev proxy is pre-configured for the CAP Java server's port `8080`.

5. Use the following credentials:

   Username: `alice`

   Password: `alice`

   The SAPUI5 app is launched in a web browser.

## Minimum Versions

| Component     | Minimum Version |
| ------------- | --------------- |
| CAP Java      | 4.9.0           |
| Java          | 21              |
| Maven         | 3.6.3           |
| `@sap/cds-dk` | 9.x             |

## Security / Disclosure

If you find a bug that might be a security problem, follow the instructions in our [security policy](https://github.com/cap-java/cds-feature-data-inspector/security/policy) to report it. Don't create GitHub issues for security-related questions or problems.

## Code of Conduct

Members, contributors, and leaders pledge to make participation in our community a harassment-free experience for everyone. By participating in this project, you agree to abide by its [Code of Conduct](https://github.com/cap-java/.github/blob/main/CODE_OF_CONDUCT.md) at all times.

## Licensing

Copyright 2025-2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors. Please see our [LICENSE](LICENSE) for copyright and license information. Detailed information including third-party components and their licensing/copyright information is available through the [REUSE tool](https://api.reuse.software/info/github.com/cap-java/cds-feature-data-inspector).
