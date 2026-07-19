package com.tang.plugin.domain.dto.match.image;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * A3-2a wrapper response for POST /api/plugin/match/image-search. Carries the normalized candidates
 * plus how the backend resolved the search: which image ({@link ImageSource}) and which text query
 * ({@link QuerySource}) were used. {@code appliedQuery} is the human-readable display value of the
 * query (null when {@link QuerySource#NONE}).
 */
@Data
@Accessors(chain = true)
public class ImageSearchResultVO {
    private List<ImageSearchProductVO> items;
    private String imageSource;
    private String querySource;
    private String appliedQuery;
}
