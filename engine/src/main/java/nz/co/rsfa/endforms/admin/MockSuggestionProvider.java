package nz.co.rsfa.endforms.admin;

import nz.co.rsfa.endforms.admin.MappingSuggestions.Alternative;
import nz.co.rsfa.endforms.admin.MappingSuggestions.Proposal;
import nz.co.rsfa.endforms.admin.MappingSuggestions.Refusal;
import nz.co.rsfa.endforms.admin.MappingSuggestions.Request;
import nz.co.rsfa.endforms.admin.MappingSuggestions.Response;
import nz.co.rsfa.endforms.admin.MappingSuggestions.SchemaColumn;
import nz.co.rsfa.endforms.admin.MappingSuggestions.TemplateField;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * NOT AI. A deterministic word-overlap heuristic that fills the suggestion contract so the review workflow can be
 * built and tested without any AI provider (D-039). Every response says {@code isMock: true}. It scores Monday column
 * titles against each field's printed label and name, boosts columns that imported MacroForge manifests use for a
 * field with the same label, and proposes checkbox conditions where a column label equals a field option.
 */
public final class MockSuggestionProvider implements MappingSuggestions.SuggestionProvider {

    private static final Set<String> STOP = Set.of("the", "of", "and", "or", "a", "an", "to", "for", "in", "on", "your",
            "please", "if", "is", "are", "any", "with", "text", "box", "check", "field", "per", "by", "be", "you", "do");
    private static final Map<String, String> SYNONYMS = Map.ofEntries(Map.entry("customer", "applicant"),
            Map.entry("borrower", "applicant"), Map.entry("client", "applicant"), Map.entry("surname", "name"),
            Map.entry("dob", "birth"), Map.entry("postal", "postal"), Map.entry("suburb", "suburb"),
            Map.entry("town", "city"), Map.entry("zip", "postcode"), Map.entry("phone", "phone"),
            Map.entry("mobile", "phone"), Map.entry("email", "email"), Map.entry("loan", "loan"));
    private final Clock clock;

    public MockSuggestionProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Response suggest(Request req) {
        List<Proposal> proposals = new ArrayList<>();
        List<Refusal> refused = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        Map<String, List<String>> refByLabel = new HashMap<>();
        for (var r : req.references()) {
            refByLabel.computeIfAbsent(norm(r.label().isBlank() ? r.pdfField() : r.label()), k -> new ArrayList<>())
                    .add(r.sourceKey() + " (" + r.templateKey() + ")");
        }
        for (TemplateField f : req.fields()) {
            if (f.alreadyMapped()) {
                continue;
            }
            if (f.protectedField() || f.declarationLike()) {
                refused.add(new Refusal(f.name(), f.protectedField() ? "signature or push button: never filled"
                        : "reads like a declaration, certification or consent: only a person decides, never suggested"));
                continue;
            }
            Set<String> words = words(f.label() + " " + f.name());
            if (f.kind().equals("checkbox") || f.kind().equals("radio")) {
                Proposal p = condition(f, req.columns(), words);
                if (p != null) {
                    proposals.add(p);
                } else {
                    unmatched.add(f.name());
                }
                continue;
            }
            List<Scored> scored = new ArrayList<>();
            List<String> refs = refByLabel.getOrDefault(norm(f.label().isBlank() ? f.name() : f.label()), List.of());
            for (SchemaColumn c : req.columns()) {
                if (!c.type().equals("text") && !c.type().equals("numbers") && !c.type().equals("date")
                        && !c.type().equals("dropdown") && !c.type().equals("status") && !c.type().equals("name")) {
                    continue;
                }
                Set<String> cw = words(c.title());
                Set<String> common = new LinkedHashSet<>(words);
                common.retainAll(cw);
                double score = common.isEmpty() ? 0 : (double) common.size() / Math.max(3, Math.min(words.size(), cw.size()) + 1);
                boolean referenced = refs.stream().anyMatch(r -> r.startsWith(c.title() + " ("));
                if (referenced) {
                    score += 0.35;
                }
                if (score > 0) {
                    scored.add(new Scored(c, Math.min(score, 0.9), common, referenced));
                }
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(s -> s.column().title()));
            if (scored.isEmpty() || scored.get(0).score() < 0.25) {
                unmatched.add(f.name());
                continue;
            }
            Scored top = scored.get(0);
            List<String> evidence = new ArrayList<>();
            if (!top.common().isEmpty()) {
                evidence.add("printed label/name shares the words " + top.common() + " with the column title");
            }
            if (top.referenced()) {
                evidence.add("an imported MacroForge mapping uses this column for a field with the same label");
            }
            List<String> ambiguities = new ArrayList<>();
            if (scored.size() > 1 && scored.get(1).score() >= top.score() - 0.1) {
                ambiguities.add("another column scores almost the same: '" + scored.get(1).column().title() + "'");
            }
            if (top.column().title().matches(".*Applicant [12].*")) {
                ambiguities.add("Applicant 1 vs Applicant 2: check which person this field belongs to");
            }
            List<Alternative> alts = scored.stream().skip(1).limit(3)
                    .map(s -> new Alternative(s.column().id(), s.column().title(), round(s.score()))).toList();
            proposals.add(new Proposal(f.name(), "column", top.column().id(), top.column().title(), null, null, null,
                    round(top.score()), evidence, ambiguities, alts));
        }
        return new Response(MappingSuggestions.CONTRACT, new MappingSuggestions.Provider("mock-heuristic", true, null,
                "MOCK: word-overlap heuristic, not an AI model. Review every proposal."),
                clock.instant().toString(), proposals, refused, unmatched);
    }

    /** Checkbox/radio: a column whose option labels contain one of the field's export values or its label. */
    private Proposal condition(TemplateField f, List<SchemaColumn> columns, Set<String> words) {
        for (SchemaColumn c : columns) {
            for (String option : f.options().isEmpty() ? List.of("Yes") : f.options()) {
                for (String label : c.labels()) {
                    boolean labelMatch = !f.label().isBlank() && norm(label).equals(norm(f.label()));
                    if (labelMatch || (!option.equals("Yes") && norm(label).equals(norm(option)))) {
                        Set<String> cw = words(c.title());
                        cw.retainAll(words);
                        List<String> amb = new ArrayList<>();
                        amb.add("confirm the column: the same label may appear in several Monday columns");
                        return new Proposal(f.name(), "condition", c.id(), c.title(), "equals", label,
                                f.options().size() > 1 || f.kind().equals("radio") ? option : null, cw.isEmpty() ? 0.35 : 0.6,
                                List.of("the Monday label '" + label + "' equals the field's " + (labelMatch ? "printed label" : "export value")),
                                amb, List.of());
                    }
                }
            }
        }
        return null;
    }

    private record Scored(SchemaColumn column, double score, Set<String> common, boolean referenced) {
    }

    static Set<String> words(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() > 1 && !STOP.contains(w) && !w.matches("\\d+")) {
                out.add(SYNONYMS.getOrDefault(w, w.endsWith("s") && w.length() > 4 ? w.substring(0, w.length() - 1) : w));
            }
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static double round(double d) {
        return Math.round(d * 100) / 100.0;
    }
}
