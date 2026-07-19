package com.tang.plugin.domain.dto.match.image;

/** Where the applied text-correction query came from (A3-2a). */
public enum QuerySource {
    /** No query applied (pure image search). */
    NONE,
    /** Query derived from the shop product title. */
    TITLE,
    /** Query produced by the vision LLM from the image. */
    LLM
}
