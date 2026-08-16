package com.skyshift.cognitiveragengine.ingestion.model.enums;

/**
 * Which flat array (texts[]/tables[]/pictures[]/groups[]) a resolved $ref pointed into.
 * GROUPs are pure containers — resolved recursively for their children, never emitted
 * themselves as a DoclingItem (see DoclingDocumentParser).
 */
public enum DoclingItemSource {
    TEXT,
    TABLE,
    PICTURE,
    GROUP
}
