package io.quarkus.qute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Predicate;

import io.quarkus.qute.SectionHelperFactory.BlockInfo;
import io.quarkus.qute.TemplateNode.Origin;

/**
 * Each section consists of one or more blocks. The main block is always present. Additional blocks start with a label
 * definition: <code>{#label param1}</code>.
 *
 * @see SectionHelperFactory#MAIN_BLOCK_NAME
 */
public final class SectionBlock implements WithOrigin, ErrorInitializer {

    public final Origin origin;

    /**
     * Id generated by the parser. {@value SectionHelperFactory#MAIN_BLOCK_NAME} for the main block.
     */
    public final String id;
    /**
     * Label used for the given part. {@value SectionHelperFactory#MAIN_BLOCK_NAME} for the main block.
     */
    public final String label;
    /**
     * An unmodifiable ordered map of parsed parameters.
     * <p>
     * Note that the order does not necessary reflect the original positions of the parameters but the parsing order.
     *
     * @see SectionHelperFactory#getParameters()
     */
    public final Map<String, String> parameters;

    /**
     * An unmodifiable ordered map of parameter expressions.
     */
    public final Map<String, Expression> expressions;

    /**
     * Section content - an immutable list of template nodes.
     */
    public List<TemplateNode> nodes;

    private final List<String> positionalParameters;

    public SectionBlock(Origin origin, String id, String label, Map<String, String> parameters,
            Map<String, Expression> expressions,
            List<TemplateNode> nodes, List<String> positionalParameters) {
        this.origin = origin;
        this.id = id;
        this.label = label;
        this.parameters = parameters;
        this.expressions = expressions;
        this.nodes = ImmutableList.copyOf(nodes);
        this.positionalParameters = positionalParameters;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /**
     *
     * @param position
     * @return the parameter for the specified position, or {@code null} if no such parameter exists
     */
    public String getParameter(int position) {
        return positionalParameters.get(position);
    }

    List<Expression> getExpressions() {
        List<Expression> expressions = new ArrayList<>();
        expressions.addAll(this.expressions.values());
        for (TemplateNode node : nodes) {
            expressions.addAll(node.getExpressions());
        }
        return expressions;
    }

    Expression findExpression(Predicate<Expression> predicate) {
        for (Expression e : this.expressions.values()) {
            if (predicate.test(e)) {
                return e;
            }
        }
        for (TemplateNode node : nodes) {
            if (node instanceof ExpressionNode) {
                Expression e = ((ExpressionNode) node).expression;
                if (predicate.test(e)) {
                    return e;
                }
            } else if (node instanceof SectionNode) {
                Expression found = ((SectionNode) node).findExpression(predicate);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    List<ParameterDeclaration> getParamDeclarations() {
        List<ParameterDeclaration> declarations = null;
        for (TemplateNode node : nodes) {
            List<ParameterDeclaration> nodeDeclarations = node.getParameterDeclarations();
            if (!nodeDeclarations.isEmpty()) {
                if (declarations == null) {
                    declarations = new ArrayList<>();
                }
                declarations.addAll(nodeDeclarations);
            }
        }
        return declarations != null ? declarations : Collections.emptyList();
    }

    TemplateNode findNode(Predicate<TemplateNode> predicate) {
        for (TemplateNode node : nodes) {
            if (predicate.test(node)) {
                return node;
            }
            if (node.isSection()) {
                SectionNode sectionNode = (SectionNode) node;
                TemplateNode found = sectionNode.findNode(predicate);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    List<TemplateNode> findNodes(Predicate<TemplateNode> predicate) {
        List<TemplateNode> ret = null;
        for (TemplateNode node : nodes) {
            if (predicate.test(node)) {
                if (ret == null) {
                    ret = new ArrayList<>();
                }
                ret.add(node);
            }
            if (node.isSection()) {
                SectionNode sectionNode = (SectionNode) node;
                List<TemplateNode> found = sectionNode.findNodes(predicate);
                if (!found.isEmpty()) {
                    if (ret == null) {
                        ret = new ArrayList<>();
                    }
                    ret.addAll(found);
                }
            }
        }
        return ret == null ? Collections.emptyList() : ret;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("SectionBlock [origin=").append(origin).append(", id=").append(id).append(", label=").append(label)
                .append("]");
        return builder.toString();
    }

    @Override
    public Origin getOrigin() {
        return origin;
    }

    void optimizeNodes(Set<TemplateNode> nodesToRemove) {
        List<TemplateNode> effectiveNodes = new ArrayList<>();
        boolean hasLineSeparator = false;
        boolean nodeIgnored = false;
        for (TemplateNode node : nodes) {
            if (node instanceof SectionNode) {
                effectiveNodes.add(node);
                ((SectionNode) node).optimizeNodes(nodesToRemove);
            } else if (node == Parser.COMMENT_NODE || nodesToRemove.contains(node)) {
                // Ignore comments and nodes for removal
                nodeIgnored = true;
            } else {
                effectiveNodes.add(node);
                if (node instanceof LineSeparatorNode) {
                    hasLineSeparator = true;
                }
            }
        }

        if (!hasLineSeparator && !nodeIgnored) {
            // No optimizations are possible
            return;
        }

        if (hasLineSeparator) {
            List<TemplateNode> finalNodes;
            // Collapse adjacent text and line separator nodes
            finalNodes = new ArrayList<>();
            List<TextNode> textGroup = null;
            for (TemplateNode node : effectiveNodes) {
                if (node instanceof TextNode) {
                    if (textGroup == null) {
                        textGroup = new ArrayList<>();
                    }
                    textGroup.add((TextNode) node);
                } else {
                    if (textGroup != null) {
                        collapseGroup(textGroup, finalNodes);
                        textGroup = null;
                    }
                    finalNodes.add(node);
                }
            }
            if (textGroup != null) {
                collapseGroup(textGroup, finalNodes);
            }
            nodes = ImmutableList.copyOf(finalNodes);
        } else if (nodeIgnored) {
            nodes = ImmutableList.copyOf(effectiveNodes);
        }
    }

    private void collapseGroup(List<TextNode> group, List<TemplateNode> finalNodes) {
        if (group.size() > 1) {
            // Collapse the group...
            StringBuilder val = new StringBuilder();
            for (TextNode textNode : group) {
                val.append(textNode.getValue());
            }
            finalNodes.add(new TextNode(val.toString(), group.get(0).getOrigin()));
        } else {
            finalNodes.add(group.get(0));
        }
    }

    static SectionBlock.Builder builder(String id, Parser parser,
            ErrorInitializer errorInitializer) {
        return new Builder(id, parser, errorInitializer).setLabel(id);
    }

    static class Builder implements BlockInfo {

        private final String id;
        private Origin origin;
        private String label;
        private Map<String, String> parameters;
        private List<String> parametersPositions = List.of();
        private final List<TemplateNode> nodes;
        private Map<String, Expression> expressions;
        private final Parser parser;
        private final ErrorInitializer errorInitializer;

        public Builder(String id, Parser parser, ErrorInitializer errorInitializer) {
            this.id = id;
            this.nodes = new ArrayList<>();
            this.parser = parser;
            this.errorInitializer = errorInitializer;
        }

        SectionBlock.Builder setOrigin(Origin origin) {
            this.origin = origin;
            return this;
        }

        SectionBlock.Builder addNode(TemplateNode node) {
            nodes.add(node);
            return this;
        }

        SectionBlock.Builder setLabel(String label) {
            this.label = label;
            return this;
        }

        SectionBlock.Builder addParameter(Entry<String, String> entry) {
            if (parameters == null) {
                parameters = new LinkedHashMap<>();
            }
            parameters.put(entry.getKey(), entry.getValue());
            return this;
        }

        SectionBlock.Builder setParametersPositions(List<String> parametersPositions) {
            this.parametersPositions = Collections.unmodifiableList(parametersPositions);
            return this;
        }

        @Override
        public Expression addExpression(String param, String value) {
            Expression expression = parser.createSectionBlockExpression(this, value);
            if (expressions == null) {
                expressions = new LinkedHashMap<>();
            }
            expressions.put(param, expression);
            return expression;
        }

        public Map<String, String> getParameters() {
            return parameters == null ? Collections.emptyMap() : Collections.unmodifiableMap(parameters);
        }

        @Override
        public String getParameter(int position) {
            return parametersPositions.get(position);
        }

        public String getLabel() {
            return label;
        }

        @Override
        public Origin getOrigin() {
            return origin;
        }

        @Override
        public TemplateException.Builder error(String message) {
            return errorInitializer.error(message);
        }

        SectionBlock build() {
            Map<String, String> parameters = this.parameters;
            if (parameters == null) {
                parameters = Collections.emptyMap();
            } else if (parameters.size() == 1) {
                parameters = Map.copyOf(parameters);
            } else {
                parameters = Collections.unmodifiableMap(parameters);
            }
            Map<String, Expression> expressions = this.expressions;
            if (expressions == null) {
                expressions = Collections.emptyMap();
            } else if (expressions.size() == 1) {
                expressions = Map.copyOf(expressions);
            } else {
                expressions = Collections.unmodifiableMap(expressions);
            }
            return new SectionBlock(origin, id, label, parameters, expressions, nodes, parametersPositions);
        }
    }

}
