package com.agentforge.task;

import com.agentforge.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Ready-made scenarios for demos and quick starts. Each one can pre-fill the form or launch immediately. */
@Component
public class TemplateCatalog {

    public record SampleEvidence(String filename, String content) {
    }

    public record Template(String id, String icon, String category, String name, String blurb, String title, String description,
                           List<String> checkpoints, String intensity, String location, List<SampleEvidence> evidence) {
        public Map<String, Object> summary() {
            return com.agentforge.common.Db.row("id", id, "icon", icon, "category", category, "name", name, "blurb", blurb, "title", title,
                    "description", description, "checkpoints", checkpoints, "intensity", intensity,
                    "location", location == null ? "" : location, "evidence", evidence.stream().map(SampleEvidence::filename).toList());
        }
    }

    private final List<Template> templates = List.of(
            new Template("product-launch", "🚀", "Product", "Product launch readiness",
                    "Launch date vs. QA capacity, with a budget memo that contradicts the plan.",
                    "Should we outsource QA to hit our launch date?",
                    "We plan to launch our mobile app in 6 weeks with a budget of $40000. We assume two contractors can finish QA by the deadline. "
                            + "The vendor contract has unclear liability terms. Customer research shows 35% of users want offline mode. Should we outsource QA?",
                    List.of("on_conflict"), "balanced", null,
                    List.of(new SampleEvidence("finance-approval-memo.txt",
                            "Finance approval memo. Approved launch budget: $32000. Survey of 500 users: 22% of users asked for offline mode. "
                                    + "QA vendor quote: 3 contractors needed for full regression coverage in 6 weeks."))),
            new Template("vendor-selection", "🏢", "Procurement", "CRM vendor selection",
                    "Two vendors, a pricing sheet and a security questionnaire.",
                    "Which CRM vendor should we choose?",
                    "We need a new CRM for a 40-person sales team within 3 months. Vendor A costs $18 per user per month and integrates with our email. "
                            + "Vendor B costs $11 per user per month but has no single sign-on. Our security policy requires single sign-on and data stored in the EU. "
                            + "We assume migration takes 4 weeks.",
                    List.of("on_conflict", "before_synthesis"), "ruthless", null,
                    List.of(new SampleEvidence("vendor-pricing.csv",
                            "vendor,price_per_user_month,sso,data_region,migration_weeks\nVendor A,$21,yes,EU,6 weeks\nVendor B,$11,no,US,3 weeks"),
                            new SampleEvidence("security-questionnaire.txt",
                                    "Vendor A: SOC 2 Type II certified, SSO via SAML, EU data residency available. "
                                            + "Vendor B: SOC 2 in progress, SSO on roadmap for next year, US data centers only."))),
            new Template("hiring", "🧑‍💻", "People", "Hiring decision",
                    "One senior engineer or two juniors, under a fixed headcount budget.",
                    "Hire one senior engineer or two junior engineers?",
                    "Our team of 5 engineers is behind on the payments roadmap by 2 months. We have a hiring budget of $150000 per year. "
                            + "A senior engineer costs about $140000; two junior engineers cost about $75000 each. Seniors ramp up in 1 month, juniors in 4 months. "
                            + "We assume our two senior staff can mentor juniors without slowing down.",
                    List.of("on_conflict"), "balanced", null,
                    List.of(new SampleEvidence("team-capacity.txt",
                            "Sprint report: senior staff spent 30% of last quarter on incident response. Mentoring capacity estimated at 4 hours per week. "
                                    + "Junior salary benchmark: $82000 per year in our city."))),
            new Template("site-selection", "📍", "Operations", "New café location",
                    "Location-dependent: shows nearby results on the map.",
                    "Where should we open our second café?",
                    "We want to open our second café near Indiranagar within 4 months. Monthly rent budget is $3000. We expect 300 customers a day if we are "
                            + "near offices and a metro station. Competition from nearby cafés is unclear. Which area nearby is best?",
                    List.of("on_conflict"), "gentle", "Indiranagar, Bengaluru",
                    List.of(new SampleEvidence("footfall-survey.txt",
                            "Footfall survey (weekday average): 220 customers a day near the metro exit, 340 a day near the tech park. "
                                    + "Average rent quoted: $3600 per month near the metro, $2800 per month near the tech park."))),
            new Template("incident", "🛠", "Engineering", "Incident post-mortem",
                    "Decide the follow-up after an outage, with a log excerpt as evidence.",
                    "What should we fix first after last night's outage?",
                    "Our checkout service was down for 45 minutes last night. We believe the cause was a database connection limit after a deploy. "
                            + "Options: roll back the connection pool change, add autoscaling, or add a deploy freeze on Fridays. We assume the deploy was the only change.",
                    List.of("on_conflict", "after_debate"), "ruthless", null,
                    List.of(new SampleEvidence("incident-log.txt",
                            "21:02 deploy v4.18 completed. 21:09 db connections at 498/500. 21:11 checkout error rate 62%. "
                                    + "21:14 feature flag 'new-recommendations' enabled by marketing. 21:47 rollback of v4.18 completed; errors persisted until 21:56 "
                                    + "when the feature flag was disabled. Outage duration: 54 minutes.")))
    );

    public List<Map<String, Object>> list() {
        return templates.stream().map(Template::summary).toList();
    }

    public Template get(String id) {
        return templates.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Template"));
    }
}
