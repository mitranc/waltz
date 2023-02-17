package org.finos.waltz.service.bulk_upload;

import org.finos.waltz.common.*;
import org.finos.waltz.common.StreamUtilities.Siphon;
import org.finos.waltz.data.EntityAliasPopulator;
import org.finos.waltz.model.Cardinality;
import org.finos.waltz.model.EntityKind;
import org.finos.waltz.model.EntityReference;
import org.finos.waltz.model.IdProvider;
import org.finos.waltz.model.assessment_definition.AssessmentDefinition;
import org.finos.waltz.model.assessment_rating.AssessmentRating;
import org.finos.waltz.model.bulk_upload.ResolutionStatus;
import org.finos.waltz.model.bulk_upload.legal_entity_relationship.*;
import org.finos.waltz.model.legal_entity.LegalEntityRelationship;
import org.finos.waltz.model.legal_entity.LegalEntityRelationshipKind;
import org.finos.waltz.model.rating.RatingSchemeItem;
import org.finos.waltz.service.assessment_definition.AssessmentDefinitionService;
import org.finos.waltz.service.assessment_rating.AssessmentRatingService;
import org.finos.waltz.service.legal_entity.LegalEntityRelationshipKindService;
import org.finos.waltz.service.legal_entity.LegalEntityRelationshipService;
import org.finos.waltz.service.permission.permission_checker.LegalEntityRelationshipPermissionChecker;
import org.finos.waltz.service.rating_scheme.RatingSchemeService;
import org.jooq.lambda.tuple.Tuple2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.util.Collections.emptySet;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.groupingBy;
import static org.finos.waltz.common.ArrayUtilities.idx;
import static org.finos.waltz.common.Checks.checkNotNull;
import static org.finos.waltz.common.CollectionUtilities.first;
import static org.finos.waltz.common.MapUtilities.groupBy;
import static org.finos.waltz.common.MapUtilities.indexBy;
import static org.finos.waltz.common.SetUtilities.*;
import static org.finos.waltz.common.StreamUtilities.mkSiphon;
import static org.finos.waltz.common.StringUtilities.isEmpty;
import static org.finos.waltz.common.StringUtilities.safeTrim;
import static org.finos.waltz.model.bulk_upload.legal_entity_relationship.AssessmentHeaderResolutionError.mkError;
import static org.finos.waltz.model.bulk_upload.legal_entity_relationship.AssessmentResolutionErrorCode.*;
import static org.finos.waltz.model.bulk_upload.legal_entity_relationship.LegalEntityRelationshipResolutionError.mkError;
import static org.finos.waltz.model.bulk_upload.legal_entity_relationship.ResolvedAssessmentHeader.mkHeader;
import static org.finos.waltz.service.bulk_upload.BulkUploadUtilities.getColumnValuesFromRows;
import static org.finos.waltz.service.bulk_upload.BulkUploadUtilities.streamRowData;
import static org.jooq.lambda.tuple.Tuple.tuple;

@Service
public class BulkUploadLegalEntityRelationshipService {

    private static final int LEGAL_ENTITY_REL_COLS_COUNT = 3;
    private final AssessmentDefinitionService assessmentDefinitionService;
    private final RatingSchemeService ratingSchemeService;
    private final EntityAliasPopulator entityAliasPopulator;
    private final LegalEntityRelationshipKindService legalEntityRelationshipKindService;
    private final LegalEntityRelationshipService legalEntityRelationshipService;

    private final AssessmentRatingService assessmentRatingService;


    @Autowired
    public BulkUploadLegalEntityRelationshipService(AssessmentDefinitionService assessmentDefinitionService,
                                                    RatingSchemeService ratingSchemeService,
                                                    EntityAliasPopulator entityAliasPopulator,
                                                    LegalEntityRelationshipKindService legalEntityRelationshipKindService,
                                                    LegalEntityRelationshipService legalEntityRelationshipService,
                                                    AssessmentRatingService assessmentRatingService) {

        checkNotNull(assessmentDefinitionService, "assessmentDefinitionService cannot be null");
        checkNotNull(ratingSchemeService, "ratingSchemeService cannot be null");
        checkNotNull(entityAliasPopulator, "entityAliasPopulator cannot be null");
        checkNotNull(legalEntityRelationshipKindService, "legalEntityRelationshipKindService cannot be null");
        checkNotNull(legalEntityRelationshipService, "legalEntityRelationshipService cannot be null");
        checkNotNull(assessmentRatingService, "assessmentRatingService cannot be null");

        this.assessmentDefinitionService = assessmentDefinitionService;
        this.ratingSchemeService = ratingSchemeService;
        this.entityAliasPopulator = entityAliasPopulator;
        this.legalEntityRelationshipKindService = legalEntityRelationshipKindService;
        this.legalEntityRelationshipService = legalEntityRelationshipService;
        this.assessmentRatingService = assessmentRatingService;
    }

    public ResolveBulkUploadLegalEntityRelationshipParameters resolve(BulkUploadLegalEntityRelationshipCommand uploadCommand) {

        LegalEntityRelationshipKind relKind = legalEntityRelationshipKindService.getById(uploadCommand.legalEntityRelationshipKindId());

        // header to data rows
        Tuple2<Tuple2<Integer, String[]>, Set<Tuple2<Integer, String[]>>> parsedRows = readRows(uploadCommand.inputString());

        Set<Tuple2<Integer, ResolvedAssessmentHeader>> headersByColumnIndex = parseAssessmentsFromHeader(parsedRows.v1);

        Set<ResolvedUploadRow> resolvedRows = parseRowData(parsedRows.v2, relKind, headersByColumnIndex);

        return ImmutableResolveBulkUploadLegalEntityRelationshipParameters.builder()
                .uploadMode(uploadCommand.uploadMode())
                .inputString(uploadCommand.inputString())
                .resolvedRows(resolvedRows)
                .build();
    }

    private Set<Tuple2<Integer, ResolvedAssessmentHeader>> assignDuplicateHeaderErrors(Set<Tuple2<Integer, ResolvedAssessmentHeader>> headersByColumnIndex) {
        Map<Tuple2<AssessmentDefinition, RatingSchemeItem>, Collection<Tuple2<Integer, ResolvedAssessmentHeader>>> columnsByDefn = groupBy(
                headersByColumnIndex,
                d -> tuple(d.v2.headerDefinition().orElse(null), d.v2.headerRating().orElse(null)));

        Set<Tuple2<Integer, ResolvedAssessmentHeader>> duplicateColumns = columnsByDefn
                .entrySet()
                .stream()
                .filter(e -> e.getValue().size() > 1)
                .flatMap(e -> e.getValue().stream())
                .collect(Collectors.toSet());

        AssessmentHeaderResolutionError duplicateColumnError = mkError(DUPLICATE_COLUMN_HEADER, "There are multiple columns sharing this definition / rating value");

        return headersByColumnIndex
                .stream()
                .map(t -> {
                    if (duplicateColumns.contains(t)) {
                        return t.map2(d -> (ResolvedAssessmentHeader) ImmutableResolvedAssessmentHeader
                                .copyOf(d)
                                .withErrors(union(d.errors(), asSet(duplicateColumnError))));
                    } else {
                        return t;
                    }
                })
                .collect(Collectors.toSet());
    }

    private Set<ResolvedUploadRow> parseRowData(Set<Tuple2<Integer, String[]>> rows,
                                                LegalEntityRelationshipKind relationshipKind,
                                                Set<Tuple2<Integer, ResolvedAssessmentHeader>> headersByColumnIndex) {

        Set<LegalEntityRelationship> existingRelationships = legalEntityRelationshipService.findByRelationshipKind(relationshipKind.id().get());
        Map<Tuple2<EntityReference, EntityReference>, EntityReference> existingRelToIdMap = indexBy(existingRelationships,
                r -> tuple(r.targetEntityReference(), r.legalEntityReference()),
                LegalEntityRelationship::entityReference);

        List<AssessmentRating> existingAssessmentRatings = assessmentRatingService.findByEntityKind(EntityKind.LEGAL_ENTITY_RELATIONSHIP_KIND, Optional.of(relationshipKind.entityReference()));
        Map<Long, Collection<Tuple2<Long, Long>>> ratingsByRelationship = groupBy(existingAssessmentRatings,
                d -> d.entityReference().id(),
                d -> tuple(d.assessmentDefinitionId(), d.ratingId()));

        Set<String> targetIdentifiers = getColumnValuesFromRows(rows, LegalEntityBulkUploadFixedColumns.ENTITY_IDENTIFIER);
        Set<String> legalEntityIdentifiers = getColumnValuesFromRows(rows, LegalEntityBulkUploadFixedColumns.LEGAL_ENTITY_IDENTIFIER);

        Map<String, EntityReference> targetIdentifierToIdMap = entityAliasPopulator.fetchEntityReferenceLookupMap(relationshipKind.targetKind(), targetIdentifiers);
        Map<String, EntityReference> legalEntityIdentifierToIdMap = entityAliasPopulator.fetchEntityReferenceLookupMap(EntityKind.LEGAL_ENTITY, legalEntityIdentifiers);

        return rows
                .stream()
                .map(row -> {
                    String[] values = row.v2;

                    ResolvedLegalEntityRelationship relationship = resolveLegalEntityRelationship(
                            relationshipKind.targetKind(),
                            targetIdentifierToIdMap,
                            legalEntityIdentifierToIdMap,
                            values,
                            existingRelToIdMap);

                    Collection<Tuple2<Long, Long>> existingRatingInfo = relationship.relationshipId()
                            .map(ratingsByRelationship::get)
                            .orElse(emptySet());

                    Set<ResolvedAssessmentRating> assessments = resolveAssessments(headersByColumnIndex, existingRatingInfo, values);

                    return ImmutableResolvedUploadRow.builder()
                            .rowNumber(row.v1)
                            .legalEntityRelationship(relationship)
                            .assessmentRatings(assessments)
                            .build();
                })
                .collect(Collectors.toSet());
    }

    // For specific DEF / RATING cols, need to check over multiple cols for single value definitions
    private Set<ResolvedAssessmentRating> assignDisallowedMultipleRatingsError(Set<ResolvedAssessmentRating> assessments) {

        Map<AssessmentDefinition, List<ResolvedAssessmentRating>> ratingsByDefinitionForRow = assessments
                .stream()
                .filter(d -> d.assessmentHeader().headerDefinition().map(def -> def.cardinality().equals(Cardinality.ZERO_ONE)).orElse(false))
                .collect(groupingBy(d -> d.assessmentHeader().headerDefinition().orElse(null)));

        Set<AssessmentDefinition> definitionsWithMultipleRatingsDisallowed = ratingsByDefinitionForRow
                .entrySet()
                .stream()
                .filter(e -> getDistinctRatings(e.getValue()).size() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        RatingResolutionError multipleRatingsDisallowedError = RatingResolutionError.mkError(RatingResolutionErrorCode.MULTIPLE_RATINGS_DISALLOWED, "There have been multiple ratings specified for a single value assessment definition");

        return map(
                assessments,
                d -> d
                        .assessmentHeader()
                        .headerDefinition()
                        .map(defn -> {
                            if (definitionsWithMultipleRatingsDisallowed.contains(defn)) {

                                Set<ResolvedRatingValue> ratingsWithDisallowedError = map(
                                        d.resolvedRatings(),
                                        r -> ImmutableResolvedRatingValue
                                                .copyOf(r)
                                                .withErrors(union(r.errors(), asSet(multipleRatingsDisallowedError))));

                                return ImmutableResolvedAssessmentRating
                                        .copyOf(d)
                                        .withResolvedRatings(ratingsWithDisallowedError);
                            } else {
                                return d;
                            }
                        })
                        .orElse(d));
    }

    private Set<RatingSchemeItem> getDistinctRatings(List<ResolvedAssessmentRating> ratings) {
        return ratings
                .stream()
                .flatMap(r -> r.resolvedRatings().stream())
                .map(ResolvedRatingValue::rating)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());
    }

    private Set<ResolvedAssessmentRating> resolveAssessments(Set<Tuple2<Integer, ResolvedAssessmentHeader>> headersByColumnIndex,
                                                             Collection<Tuple2<Long, Long>> existingRatingInfo,
                                                             String[] values) {

        Set<ResolvedAssessmentRating> assessmentRatingsForRow = headersByColumnIndex
                .stream()
                .map(header -> {
                    String ratingValue = safeTrim(idx(values, header.v1, null));

                    if (header.v2.headerRating().isPresent() && !isEmpty(ratingValue)) {
                        return mkResolvedRatingForRatingColumn(header, ratingValue, existingRatingInfo);
                    } else {
                        return mkResolvedRatingForAssessmentDefinitionColumn(header, ratingValue, existingRatingInfo);
                    }
                })
                .collect(Collectors.toSet());

        return assignDisallowedMultipleRatingsError(assessmentRatingsForRow);
    }

    private ResolvedAssessmentRating mkResolvedRatingForAssessmentDefinitionColumn(Tuple2<Integer, ResolvedAssessmentHeader> header,
                                                                                   String ratingValue,
                                                                                   Collection<Tuple2<Long, Long>> existingRatingInfo) {

        Map<Long, Collection<RatingSchemeItem>> ratingSchemeItemsBySchemeId = groupBy(ratingSchemeService.getAllRatingSchemeItems(), RatingSchemeItem::ratingSchemeId);

        Set<RatingResolutionError> errors = new HashSet<>();

        Long defnId = header.v2.headerDefinition().flatMap(IdProvider::id).orElse(null);
        String[] ratingValues = ratingValue.split(";");

        Set<ResolvedRatingValue> ratings = Stream.of(ratingValues)
                .map(ratingString -> {

                    Optional<RatingSchemeItem> ratingSchemeItem = determineRating(ratingSchemeItemsBySchemeId, safeTrim(ratingString), header.v2.headerDefinition());

                    if (OptionalUtilities.isEmpty(ratingSchemeItem)) {
                        errors.add(RatingResolutionError.mkError(RatingResolutionErrorCode.RATING_VALUE_NOT_FOUND, format("Could not identify rating value from: '%s'", ratingString)));
                    }

                    boolean ratingExists = existingRatingInfo.contains(tuple(defnId, ratingSchemeItem.flatMap(IdProvider::id)));
                    ResolutionStatus status = determineResolutionStatus(ratingExists, errors);

                    return ImmutableResolvedRatingValue.builder()
                            .rating(ratingSchemeItem)
                            .errors(errors)
                            .status(status)
                            .build();
                })
                .collect(Collectors.toSet());

        return ImmutableResolvedAssessmentRating.builder()
                .columnIndex(header.v1)
                .assessmentHeader(header.v2)
                .resolvedRatings(ratings)
                .build();
    }

    private ResolvedAssessmentRating mkResolvedRatingForRatingColumn(Tuple2<Integer, ResolvedAssessmentHeader> header,
                                                                     String ratingValue,
                                                                     Collection<Tuple2<Long, Long>> existingRatingInfo) {

        String comment = ratingValue.equalsIgnoreCase("X") || ratingValue.equalsIgnoreCase("Y")
                ? null
                : ratingValue;

        Long defnId = header.v2.headerDefinition().flatMap(IdProvider::id).orElse(null);
        Long ratingId = header.v2.headerRating().flatMap(IdProvider::id).orElse(null);

        boolean existingRating = existingRatingInfo.contains(tuple(defnId, ratingId));

        ResolutionStatus status = determineResolutionStatus(existingRating, emptySet());

        ImmutableResolvedRatingValue resolvedRatingValue = ImmutableResolvedRatingValue.builder()
                .rating(header.v2.headerRating().get())
                .comment(Optional.ofNullable(comment))
                .errors(emptySet())
                .status(status)
                .build();

        return ImmutableResolvedAssessmentRating.builder()
                .columnIndex(header.v1)
                .assessmentHeader(header.v2)
                .resolvedRatings(asSet(resolvedRatingValue))
                .build();
    }

    private ResolvedLegalEntityRelationship resolveLegalEntityRelationship(EntityKind targetKind,
                                                                           Map<String, EntityReference> targetIdentifierToIdMap,
                                                                           Map<String, EntityReference> legalEntityIdentifierToIdMap,
                                                                           String[] values,
                                                                           Map<Tuple2<EntityReference, EntityReference>, EntityReference> existingRelToIdMap) {

        String legalEntityIdentifier = safeTrim(idx(values, LegalEntityBulkUploadFixedColumns.LEGAL_ENTITY_IDENTIFIER, null));
        String entityIdentifier = safeTrim(idx(values, LegalEntityBulkUploadFixedColumns.ENTITY_IDENTIFIER, null));
        String comment = idx(values, LegalEntityBulkUploadFixedColumns.COMMENT, null);

        EntityReference legalEntityRef = legalEntityIdentifierToIdMap.get(legalEntityIdentifier);
        EntityReference targetEntityRef = targetIdentifierToIdMap.get(entityIdentifier);

        Set<LegalEntityRelationshipResolutionError> errors = new HashSet<>();

        if (legalEntityRef == null) {
            errors.add(mkError(LegalEntityResolutionErrorCode.LEGAL_ENTITY_NOT_FOUND, format("Legal entity '%s' cannot be identified", legalEntityIdentifier)));
        }

        if (targetEntityRef == null) {
            errors.add(mkError(LegalEntityResolutionErrorCode.TARGET_ENTITY_NOT_FOUND, format("%s '%s' cannot be identified", targetKind.prettyName(), legalEntityIdentifier)));
        }

        EntityReference existingRel = existingRelToIdMap.get(tuple(targetEntityRef, legalEntityRef));
        Optional<Long> relationshipId = ofNullable(existingRel).map(EntityReference::id);

        ResolutionStatus status = determineResolutionStatus(relationshipId.isPresent(), errors);

        return ImmutableResolvedLegalEntityRelationship
                .builder()
                .legalEntityReference(ofNullable(legalEntityRef))
                .targetEntityReference(ofNullable(targetEntityRef))
                .comment(Optional.ofNullable(comment))
                .errors(errors)
                .status(status)
                .relationshipId(relationshipId)
                .build();
    }

    public static <T extends BulkUploadError> ResolutionStatus determineResolutionStatus(boolean exists,
                                                                                         Set<T> errors) {
        if (!CollectionUtilities.isEmpty(errors)) {
            return ResolutionStatus.ERROR;
        } else if (exists) {
            return ResolutionStatus.EXISTING;
        } else {
            return ResolutionStatus.NEW;
        }
    }

    public Set<Tuple2<Integer, ResolvedAssessmentHeader>> parseAssessmentsFromHeader(Tuple2<Integer, String[]> headerRow) {

        if (headerRow.v2.length <= LEGAL_ENTITY_REL_COLS_COUNT) {
            return emptySet();
        }

        List<AssessmentDefinition> definitions = assessmentDefinitionService.findByEntityKind(EntityKind.LEGAL_ENTITY_RELATIONSHIP);

        Map<String, AssessmentDefinition> definitionsByExternalId = definitions
                .stream()
                .filter(d -> d.externalId().isPresent())
                .collect(Collectors.toMap(d -> d.externalId().get().toLowerCase(), d -> d));

        Map<String, AssessmentDefinition> definitionsByName = indexBy(definitions, d -> d.name().toLowerCase(), d -> d);

        Map<Long, Collection<RatingSchemeItem>> ratingSchemeItemsBySchemeId = groupBy(ratingSchemeService.getAllRatingSchemeItems(), RatingSchemeItem::ratingSchemeId);

        AtomicInteger columnIndex = new AtomicInteger(LEGAL_ENTITY_REL_COLS_COUNT);

        Set<Tuple2<Integer, ResolvedAssessmentHeader>> headersByColumnIndex = Stream.of(headerRow.v2)
                .skip(LEGAL_ENTITY_REL_COLS_COUNT)
                .map(headerString -> {
                    ResolvedAssessmentHeader header = determineAssessmentByOffset(headerString, definitionsByExternalId, definitionsByName, ratingSchemeItemsBySchemeId);
                    return tuple(columnIndex.getAndIncrement(), header);
                })
                .collect(Collectors.toSet());

        return assignDuplicateHeaderErrors(headersByColumnIndex);
    }

    private ResolvedAssessmentHeader determineAssessmentByOffset(String headerString,
                                                                 Map<String, AssessmentDefinition> definitionsByExternalId,
                                                                 Map<String, AssessmentDefinition> definitionsByName,
                                                                 Map<Long, Collection<RatingSchemeItem>> ratingSchemeItemsBySchemeId) {

        Set<AssessmentHeaderResolutionError> errors = new HashSet<>();

        if (isEmpty(headerString)) {
            errors.add(mkError(NO_VALUE_PROVIDED, "Header assessment is not provided"));
            return mkHeader(Optional.empty(), Optional.empty(), errors, ResolutionStatus.ERROR);
        }

        String[] assessmentHeader = headerString.split("/");

        String definitionString = safeTrim(idx(assessmentHeader, 0, null));

        if (isEmpty(definitionString)) {
            errors.add(mkError(HEADER_DEFINITION_NOT_FOUND, "No assessment definition header provided"));
            return mkHeader(Optional.empty(), Optional.empty(), errors, ResolutionStatus.ERROR);
        }

        Optional<AssessmentDefinition> definition = determineDefinition(definitionsByExternalId, definitionsByName, definitionString);

        if (OptionalUtilities.isEmpty(definition)) {
            errors.add(mkError(HEADER_DEFINITION_NOT_FOUND, format("Could not identify an assessment definition with external id or name '%s'", definitionString)));
            return mkHeader(Optional.empty(), Optional.empty(), errors, ResolutionStatus.ERROR);
        }

        String ratingString = safeTrim(idx(assessmentHeader, 1, null));

        if (isEmpty(ratingString)) {
            return mkHeader(definition, Optional.empty(), errors, ResolutionStatus.NEW); //Does resolution status besides error matter for headers?
        }

        Optional<RatingSchemeItem> rating = determineRating(ratingSchemeItemsBySchemeId, ratingString, definition);

        if (OptionalUtilities.isEmpty(rating)) {
            errors.add(mkError(HEADER_RATING_NOT_FOUND, format("Could not identify an assessment rating with external id or name '%s'", ratingString)));
            return mkHeader(definition, Optional.empty(), errors, ResolutionStatus.ERROR);
        }

        return mkHeader(definition, rating, errors, ResolutionStatus.NEW);
    }

    private Optional<RatingSchemeItem> determineRating(Map<Long, Collection<RatingSchemeItem>> ratingSchemeItemsBySchemeId,
                                                       String ratingString,
                                                       Optional<AssessmentDefinition> definition) {

        Collection<RatingSchemeItem> schemeItems = definition
                .map(defn -> ratingSchemeItemsBySchemeId.getOrDefault(defn.ratingSchemeId(), emptySet()))
                .orElse(emptySet());

        Map<String, RatingSchemeItem> ratingSchemeItemsByExternalId = schemeItems
                .stream()
                .filter(d -> d.externalId().isPresent())
                .collect(Collectors.toMap(d -> d.externalId().get().toLowerCase(), d -> d));

        Map<String, RatingSchemeItem> ratingSchemeItemsByName = indexBy(schemeItems, d -> d.name().toLowerCase());

        return Optional.ofNullable(ratingSchemeItemsByExternalId
                .getOrDefault(
                        ratingString.toLowerCase(),
                        ratingSchemeItemsByName.get(ratingString.toLowerCase())));
    }

    private Optional<AssessmentDefinition> determineDefinition(Map<String, AssessmentDefinition> definitionsByExternalId, Map<String, AssessmentDefinition> definitionsByName, String definitionString) {
        return ofNullable(
                definitionsByExternalId
                        .getOrDefault(
                                definitionString.toLowerCase(),
                                definitionsByName.get(definitionString.toLowerCase())));
    }


    public static Tuple2<Tuple2<Integer, String[]>, Set<Tuple2<Integer, String[]>>> readRows(String inputString) {

        if (isEmpty(inputString)) {
            throw new IllegalStateException("Cannot parse empty data string");
        }

        Siphon<Tuple2<Integer, String[]>> headerRowSiphon = mkSiphon(t -> t.v1.equals(1));

        Set<Tuple2<Integer, String[]>> dataRows = streamRowData(inputString)
                .filter(headerRowSiphon)
                .collect(Collectors.toSet());

        if (headerRowSiphon.getResults().size() != 1) {
            throw new IllegalStateException("Must have one header row");
        }

        return tuple(first(headerRowSiphon.getResults()), dataRows);
    }

    public void ensureUserHasAdminRights(long legalEntityRelationshipKindId, String username) {
        LegalEntityRelationshipKind relKind = legalEntityRelationshipKindService.getById(legalEntityRelationshipKindId);
    }
}
