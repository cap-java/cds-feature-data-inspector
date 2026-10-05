// Annotations for the sample schema.

using {datainspector.test.db} from './schema';

// Category is hidden — it should NOT appear in the Data Inspector entity list
annotate db.Category with @HideFromDataInspector;

// phoneNumber should be hidden from element list of Order
annotate db.Order : phoneNumber with @HideFromDataInspector;

// hiddenField should not appear in CdsCoreTypes element list
annotate db.CdsCoreTypes : hiddenField with @HideFromDataInspector;

// CAP Java's audit log handler requires @PersonalData.EntitySemantics on any entity
// that carries @PersonalData.IsPotentiallySensitive elements.
// 'DataSubjectDetails' means Order holds PII fields but the data subject
// is identified by its own key (no separate subject entity needed here).
annotate db.Order with @PersonalData.EntitySemantics: 'DataSubjectDetails';

// The data subject is identified by the Order key (ID from cuid)
annotate db.Order : ID with @PersonalData.FieldSemantics: 'DataSubjectID';

// Sensitive fields — trigger audit log emission when read via Data Inspector
annotate db.Order : phoneNumber with @PersonalData.IsPotentiallySensitive;
annotate db.Order : address     with @PersonalData.IsPotentiallySensitive;
annotate db.CdsCoreTypes : date with @PersonalData.IsPotentiallySensitive;
