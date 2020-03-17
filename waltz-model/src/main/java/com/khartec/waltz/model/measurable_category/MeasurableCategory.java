/*
 * Waltz - Enterprise Architecture
 * Copyright (C) 2016, 2017, 2018, 2019 Waltz open source project
 * See README.md for more information
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific
 *
 */

package com.khartec.waltz.model.measurable_category;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.khartec.waltz.model.*;
import com.khartec.waltz.model.rating.RatingScheme;
import com.khartec.waltz.model.user.SystemRole;
import org.immutables.value.Value;

/**
 * A measurable category represents a classifier for a hierarchy of
 * {@link com.khartec.waltz.model.measurable.Measurable} items.
 * Together they can be thought of as a taxonomy.  Common categories include:
 *
 * <ul>
 *     <li>Service</li>
 *     <li>Function</li>
 *     <li>Product</li>
 * </ul>
 */
@Value.Immutable
@JsonSerialize(as = ImmutableMeasurableCategory.class)
@JsonDeserialize(as = ImmutableMeasurableCategory.class)
public abstract class MeasurableCategory implements
        IdProvider,
        NameProvider,
        DescriptionProvider,
        ExternalIdProvider,
        LastUpdatedProvider,
        RagNamesProvider,
        EntityKindProvider {


    @Value.Default
    public EntityKind kind() { return EntityKind.MEASURABLE_CATEGORY; }

    /**
     * Indicates if the measurables in the category may be edited from within the tool.
     * This should only be enabled for taxonomies which are entirely managed from within
     * Waltz.
     *
     * @return true if this measurables in this category can be edited
     */
    @Value.Default
    public boolean editable() {
        return false;
    }

    /**
     * @return role required for editing measurable ratings against this category
     */
    @Value.Default
    public String ratingEditorRole() {
        return SystemRole.RATING_EDITOR.name();
    }

    /**
     * A category is linked to a Rating Scheme which provides a mechanism to describe
     * application alignments to this category.  These schemes are typically variants
     * of RAG ratings, or investment ratings.
     *
     * @return id which links to a {@link RatingScheme}
     */
    public abstract long ratingSchemeId();

}
