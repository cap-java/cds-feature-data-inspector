// Sample services for cap-data-inspector-java.
// Ported from test/srv/TestServices.cds in the Node.js project.
//
// These services make the sample entities visible in the Data Inspector
// under dataSource='service', while the raw db entities show as dataSource='db'.

namespace datainspector.test.srv;

using {datainspector.test.db as db} from '../db/schema';

// ── Opt in to the cds-feature-data-inspector plugin's CDS model ──────────────
// This 'using' triggers `cds build` to include the plugin's DataInspectorService.
// The plugin's CDS files are extracted from its JAR into target/cds/com.sap.cds/cds-feature-data-inspector/
// by the cds.resolve execution declared in this test-app's pom.xml.
// The import path follows the official CAP Java convention: '<groupId>/<artifactId>'.
using from 'com.sap.cds/cds-feature-data-inspector';

// ProductService: exposes Product (with draft) and Category, plus a transient entity
service ProductService {

    @odata.draft.enabled
    entity Product  as select from db.Product;

    entity Category as projection on db.Category;

    // @cds.persistence.skip inside a service → dataSource='service' in Data Inspector
    @cds.persistence.skip
    @title: 'Product Discount (transient)'
    entity ProductDiscount {
        key productName : String;
            discount    : Integer not null;
    }
}

// OrderService: exposes Order (with draft), OrderItem, and Product
service OrderService {

    @odata.draft.enabled
    entity Order     as select from db.Order;

    entity OrderItem as select from db.OrderItem;
    entity Product   as projection on db.Product;
}

// FoodService: demonstrates @cds.query.limit pagination annotations
service FoodService {

    // Data Inspector respects these limits: default page = 10 rows, max = 20
    @cds.query.limit: {
        default: 10,
        max    : 20
    }
    entity Food as projection on db.Food;
}