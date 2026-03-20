package com.janyee.agent.infra.prompt;

import com.janyee.agent.runtime.skill.SkillPrompt;
import com.janyee.agent.workspace.WorkspaceKnowledgeFile;

import java.util.List;

public final class BuiltinDocumentWorkflowCatalog {

    private BuiltinDocumentWorkflowCatalog() {
    }

    public static List<WorkspaceKnowledgeFile> knowledgeFiles() {
        return List.of(
                new WorkspaceKnowledgeFile(
                        "builtin/document-generation/overview.md",
                        """
                        # Document Generation Workflow

                        When the user asks for a deliverable such as a markdown report, Word document, Excel workbook, or PowerPoint presentation, treat it as a structured document-generation task rather than an ordinary chat response.

                        Standard workflow:
                        1. Clarify the target output type: markdown, word, excel, ppt.
                        2. Gather required data from tools such as db.query, file.read, http.fetch, workspace.path, or existing knowledge.
                        3. Organize the output into sections, tables, and charts before generating the final file.
                        4. If charts or tables are needed, prefer:
                           - db.query -> table.render for tabular deliverables
                           - db.query -> chart.echarts for chart deliverables
                        5. After the structure is ready, call the correct artifact tool:
                           - artifact.markdown
                           - artifact.word
                           - artifact.excel
                           - artifact.ppt
                        6. The final natural-language answer should be short. It should tell the user what was generated and highlight the key findings instead of repeating the whole file body.
                        """
                ),
                new WorkspaceKnowledgeFile(
                        "builtin/document-generation/report-template.md",
                        """
                        # Generic Report Template

                        Recommended report structure:
                        - Title
                        - Background / Objective
                        - Data Source
                        - Method / Query Logic
                        - Key Findings
                        - Detailed Analysis
                        - Tables / Charts
                        - Risks / Assumptions
                        - Conclusion
                        - Next Actions

                        Writing rules:
                        - Be concise and factual.
                        - Prefer numbered findings when there are multiple conclusions.
                        - If data is partial, say so explicitly.
                        - If a chart or table already exists in the session, reference it briefly instead of copying all rows again.
                        """
                ),
                new WorkspaceKnowledgeFile(
                        "builtin/document-generation/ppt-template.md",
                        """
                        # PPT Template Guidance

                        Recommended slide sequence:
                        1. Cover: title, topic, date, scope
                        2. Executive Summary: 3-5 key findings
                        3. Data Scope / Definitions
                        4. Main Comparison Chart
                        5. Secondary Analysis
                        6. Risks / Notes
                        7. Final Recommendation

                        PPT rules:
                        - Each slide should have one main message.
                        - Prefer short bullet points.
                        - Charts should support the headline, not replace it.
                        - If chart.echarts result exists, summarize the insight and convert it into a slide-friendly narrative before calling artifact.ppt.
                        """
                ),
                new WorkspaceKnowledgeFile(
                        "builtin/document-generation/excel-template.md",
                        """
                        # Excel Generation Guidance

                        Use Excel when the user needs:
                        - raw export
                        - structured table delivery
                        - sortable/filterable result set
                        - multi-column statistical output

                        Excel rules:
                        - Columns must be explicit and stable.
                        - Preserve source field names when possible.
                        - Prefer db.query result columns directly.
                        - If a summary is needed, describe it in the chat response and keep the workbook data-oriented.
                        """
                ),
                new WorkspaceKnowledgeFile(
                        "builtin/document-generation/word-template.md",
                        """
                        # Word Document Guidance

                        Use Word when the user needs:
                        - formal report
                        - meeting memo
                        - plan /方案
                        - narrative documentation

                        Word rules:
                        - Use headings and short paragraphs.
                        - Start with purpose and scope.
                        - Include evidence from tables/charts, but do not dump raw JSON.
                        - If there are chart insights, mention them narratively and keep the body readable.
                        """
                )
        );
    }

    public static List<SkillPrompt> skillPrompts() {
        return List.of(
                new SkillPrompt(
                        "skill.document.workflow",
                        "Drive structured document/report generation from user intent to final downloadable artifact.",
                        """
                        When the user requests any deliverable file or formal output, use this workflow:
                        1. Detect output intent: markdown, word, excel, ppt.
                        2. Gather data first. Do not create the artifact before data and structure are ready.
                        3. If statistics are requested, use db.query.
                        4. If the user wants a table in the final deliverable, use table.render or reuse db.query result columns/rows.
                        5. If the user wants a chart, use chart.echarts before generating the final file.
                        6. Then generate exactly one final artifact file using the matching artifact tool.
                        7. Final assistant text should briefly summarize what was generated and the core findings.
                        """
                ),
                new SkillPrompt(
                        "skill.markdown.generate",
                        "Generate markdown reports and notes with stable sectioning.",
                        """
                        For markdown output:
                        - Build a clear title and section structure first.
                        - Prefer markdown headings, bullet lists, and tables.
                        - Use artifact.markdown to save the final deliverable.
                        - Do not return the whole markdown body in chat if the file has already been generated.
                        """
                ),
                new SkillPrompt(
                        "skill.word.generate",
                        "Generate formal Word documents from analysis results.",
                        """
                        For Word output:
                        - Produce a report-style structure with title, context, findings, and conclusion.
                        - Use concise prose and section headings.
                        - If data was queried, summarize the evidence and then call artifact.word.
                        """
                ),
                new SkillPrompt(
                        "skill.excel.generate",
                        "Generate Excel workbooks for data delivery.",
                        """
                        For Excel output:
                        - Prefer direct structured columns and rows.
                        - Use db.query result columns and rows whenever possible.
                        - If the user requests export/download, call artifact.excel with explicit columns and rows.
                        """
                ),
                new SkillPrompt(
                        "skill.ppt.generate",
                        "Generate presentation-style outputs from findings and charts.",
                        """
                        For PowerPoint output:
                        - Create a short narrative first: topic, findings, recommendation.
                        - If a chart is needed, generate it first and summarize its insight.
                        - Then call artifact.ppt with slide-friendly content, not raw SQL or raw JSON.
                        """
                )
        );
    }
}
