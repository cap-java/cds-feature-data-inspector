// Entry point for the cds-feature-data-inspector CDS model.
//
// A consumer app opts into the plugin via:
//     using from 'com.sap.cds/cds-feature-data-inspector';
//
// The CAP tooling resolves this to the JAR-extracted folder
// `target/cds/com.sap.cds/cds-feature-data-inspector/` in the consumer's
// project and then looks for an `index.cds` file (Node-module convention).
// This file therefore acts as the sole entry point and pulls in the
// service definition + its annotations.

using from './DataInspectorService';
using from './DataInspectorServiceAnnotations';