// Sample database schema for cap-data-inspector-java

namespace datainspector.test.db;

using {
    cuid,
    managed
} from '@sap/cds/common';

@title: 'Products'
entity Product : cuid, managed {
    productName : String  not null;
    description : String;
    quantity    : Integer not null;
    mrp         : Double  not null;
    category    : Association to one Category;
}

@title: 'Categories'
entity Category : cuid {
    name : String;
}

@title: 'Orders'
entity Order : cuid, managed {
    totalAmount : Double;
    status      : String   not null;
    date        : DateTime;
    // phoneNumber is sensitive — annotated in annotations.cds
    phoneNumber : String   not null;
    // address is sensitive — annotated in annotations.cds
    address     : String   not null;
    orderItem   : Composition of many OrderItem
                      on orderItem.order = $self;
}

@title: 'Order Items'
entity OrderItem : cuid, managed {
    itemName  : String  not null;
    quantity  : Integer;
    itemPrice : Double;
    order     : Association to one Order;
    product   : Association to one Product;
}

// Exercises all CDS built-in scalar types so the Data Inspector can show them
@title: 'CDS Core Types Demo'
entity CdsCoreTypes {
    key uuid        : UUID;
        boolValue   : Boolean  not null;
        integer     : Integer  default 8;
        int16       : Int16;
        int32       : Int32;
        int64       : Int64;
        decimal     : Decimal;
        doubleVal   : Double;
        date        : Date;
        time        : Time;
        dateTime    : DateTime;
        timestamp   : Timestamp;
        string      : String(88);
        largeString : LargeString;
        // hiddenField is annotated @HideFromDataInspector in annotations.cds
        hiddenField : String;
    virtual virtualField : String;
}

@title: 'Hello World — Composite Key Demo'
entity HelloWorld {
    key helloId  : String;
    key worldId  : String;
    key otherId  : String;
        quote    : String;
        color    : String;
        age      : Integer;
}

// @cds.persistence.skip: entity with no DB table — dataSource shows as 'unknown'
@title   : 'Produck (persistence.skip demo)'
@cds.persistence.skip
entity Produck {
    key id   : String;
        name : String;
}

@title: 'Food — Pagination Demo'
entity Food {
    key id          : String;
        name        : String;
        ingredients : String;
        description : String;
}
