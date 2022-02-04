/*
 * Copyright (C) 2020 Graylog, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Server Side Public License, version 1,
 * as published by MongoDB, Inc.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Server Side Public License for more details.
 *
 * You should have received a copy of the Server Side Public License
 * along with this program. If not, see
 * <http://www.mongodb.com/licensing/server-side-public-license>.
 */
package org.graylog.plugins.views.search.validation;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.queryparser.classic.ParseException;
import org.graylog.plugins.views.search.ParameterProvider;
import org.graylog.plugins.views.search.Query;
import org.graylog.plugins.views.search.elasticsearch.QueryStringDecorators;
import org.graylog.plugins.views.search.errors.SearchException;
import org.graylog.plugins.views.search.errors.UnboundParameterError;
import org.graylog.plugins.views.search.rest.MappedFieldTypeDTO;
import org.graylog2.indexer.fieldtypes.FieldTypes;
import org.graylog2.indexer.fieldtypes.MappedFieldTypesService;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Singleton
public class QueryValidationServiceImpl implements QueryValidationService {

    private final LuceneQueryParser luceneQueryParser;
    private final MappedFieldTypesService mappedFieldTypesService;
    private final QueryStringDecorators queryStringDecorators;

    @Inject
    public QueryValidationServiceImpl(LuceneQueryParser luceneQueryParser, MappedFieldTypesService mappedFieldTypesService, QueryStringDecorators queryStringDecorators) {
        this.luceneQueryParser = luceneQueryParser;
        this.mappedFieldTypesService = mappedFieldTypesService;
        this.queryStringDecorators = queryStringDecorators;
    }

    @Override
    public ValidationResponse validate(ValidationRequest req) {
        // caution, there are two validation steps!
        // the validation uses query with _non_replaced parameters, as is, to be able to track the exact positions of errors
        final String rawQuery = req.query().queryString();

        if (StringUtils.isEmpty(rawQuery)) {
            return ValidationResponse.ok();
        }

        String decorated;
        try {
            // but we want to trigger the decorators as well, because they may trigger additional exceptions
            decorated = decoratedQuery(req);
        } catch (SearchException searchException) {
            return ValidationResponse.error(toExplanation(req.query().queryString(), searchException));
        }

        try {
            final ParsedQuery parsedQuery = luceneQueryParser.parse(rawQuery);
            Set<MappedFieldTypeDTO> availableFields = mappedFieldTypesService.fieldTypesByStreamIds(req.streams(), req.timerange());

            final List<ParsedTerm> unknownFields = getUnknownFields(parsedQuery, availableFields);
            final List<ParsedTerm> invalidOperators = parsedQuery.invalidOperators();
            final List<ValidationMessage> explanations = getExplanations(unknownFields, invalidOperators);

            explanations.addAll(validateQueryValues(rawQuery, decorated, availableFields));

            return explanations.isEmpty()
                    ? ValidationResponse.ok()
                    : ValidationResponse.warning(explanations);

        } catch (ParseException e) {
            return ValidationResponse.error(toExplanation(rawQuery, e));
        }
    }

    private List<ValidationMessage> validateQueryValues(String rawQuery, String decorated, Set<MappedFieldTypeDTO> availableFields) {
        try {
            final ParsedQuery parsedQuery = luceneQueryParser.parse(decorated);
            final Map<String, MappedFieldTypeDTO> fields = availableFields.stream().collect(Collectors.toMap(MappedFieldTypeDTO::name, Function.identity()));
            return parsedQuery.terms().stream().map(term -> validateValue(term, fields)).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList());
        } catch (ParseException e) {
            // TODO! Handle exception!
        }

        return Collections.emptyList();
    }

    private Optional<ValidationMessage> validateValue(ParsedTerm t, Map<String, MappedFieldTypeDTO> fields) {
        final MappedFieldTypeDTO fieldType = fields.get(t.getRealFieldName());
        if (!typeMatching(fieldType, t.value())) {
            return Optional.of(ValidationMessage.builder()
                    .errorType("Invalid data type")
                    .errorMessage(String.format(Locale.ROOT, "Type of %s is %s, cannot use value %s", t.getRealFieldName(), fieldType.type().type(), t.value()))
                    .build());
        }
        return Optional.empty();
    }

    private boolean typeMatching(MappedFieldTypeDTO type, String value) {
        return Optional.ofNullable(type)
                .map(MappedFieldTypeDTO::type)
                .map(FieldTypes.Type::validationFunction)
                .map(validator -> validateValue(validator, value))
                .orElse(true);
    }

    private Boolean validateValue(Predicate<String> validator, String value) {
        try {
            return validator.test(value);
        } catch (Exception e) {
            return false;
        }
    }

    private List<ValidationMessage> toExplanation(String query, SearchException searchException) {
        if (searchException.error() instanceof UnboundParameterError) {
            final UnboundParameterError error = (UnboundParameterError) searchException.error();
            final List<SubstringMultilinePosition> positions = SubstringMultilinePosition.compute(query, "$" + error.parameterName() + "$");
            if (!positions.isEmpty()) {
                return positions.stream()
                        .map(p -> ValidationMessage.builder()
                                .errorType("Parameter error")
                                .errorMessage(error.description())
                                .beginLine(p.getLine())
                                .endLine(p.getLine())
                                .beginColumn(p.getBeginColumn())
                                .endColumn(p.getEndColumn())
                                .build())
                        .collect(Collectors.toList());
            }
        }
        return Collections.singletonList(ValidationMessage.fromException(query, searchException));
    }

    private List<ValidationMessage> toExplanation(final String query, final ParseException parseException) {
        return Collections.singletonList(ValidationMessage.fromException(query, parseException));
    }

    private List<ValidationMessage> getExplanations(List<ParsedTerm> unknownFields, List<ParsedTerm> invalidOperators) {
        List<ValidationMessage> messages = new ArrayList<>();

        unknownFields.stream().map(f -> {
            final ValidationMessage.Builder message = ValidationMessage.builder()
                    .errorType("Unknown field")
                    .errorMessage("Query contains unknown field: " + f.getRealFieldName());

            f.tokens().stream().findFirst().ifPresent(t -> {
                message.beginLine(t.beginLine());
                message.beginColumn(t.beginColumn());
                message.endLine(t.endLine());
                message.endColumn(t.endColumn());
            });

            return message.build();

        }).forEach(messages::add);

        invalidOperators.stream()
                .map(token -> {
                    final String errorMessage = String.format(Locale.ROOT, "Query contains invalid operator \"%s\". Both AND / OR operators have to be written uppercase", token.value());
                    final ValidationMessage.Builder message = ValidationMessage.builder()
                            .errorType("Invalid operator")
                            .errorMessage(errorMessage);
                    token.tokens().stream().findFirst().ifPresent(t -> {
                        message.beginLine(t.beginLine());
                        message.beginColumn(t.beginColumn());
                        message.endLine(t.endLine());
                        message.endColumn(t.endColumn());
                    });
                    return message.build();
                })
                .forEach(messages::add);
        return messages;
    }

    private List<ParsedTerm> getUnknownFields(ParsedQuery query, Set<MappedFieldTypeDTO> availableFields) {
        final Set<String> availableFieldsNames = availableFields
                .stream()
                .map(MappedFieldTypeDTO::name)
                .collect(Collectors.toSet());

        return query.terms().stream()
                .filter(t -> !t.isDefaultField())
                .filter(term -> !availableFieldsNames.contains(term.getRealFieldName()))
                .collect(Collectors.toList());
    }

    private String decoratedQuery(ValidationRequest req) {
        ParameterProvider parameterProvider = (name) -> req.parameters().stream().filter(p -> Objects.equals(p.name(), name)).findFirst();
        final Query query = Query.builder().query(req.query()).timerange(req.timerange()).build();
        return this.queryStringDecorators.decorate(req.getCombinedQueryWithFilter(), parameterProvider, query);
    }
}
