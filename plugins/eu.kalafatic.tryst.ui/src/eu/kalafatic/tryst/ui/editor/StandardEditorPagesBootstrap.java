package eu.kalafatic.tryst.ui.editor;

import eu.kalafatic.tryst.ui.editor.page.*;

/**
 * Bootstrapper populating {@link TrystEditorPageRegistry} with the standard TRYST workbench pages,
 * including Task Details and Database Viewer.
 */
public final class StandardEditorPagesBootstrap {

    private StandardEditorPagesBootstrap() {}

    public static void initializeStandardPages() {
        TrystEditorPageRegistry reg = TrystEditorPageRegistry.getInstance();

        reg.registerPage(new TrystEditorPageDescriptor(
                OverviewDashboardPage.ID, "Overview", 10, OverviewDashboardPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                ProjectExplorerPage.ID, "Project Explorer", 20, ProjectExplorerPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                TaskManagementPage.ID, "Task Management", 30, TaskManagementPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                TaskDetailsPage.ID, "Task Details", 35, TaskDetailsPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                StudioEditorPage.ID, "TRYST Studio", 38, StudioEditorPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                TaskExecutionPage.ID, "Task Execution", 40, TaskExecutionPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                CodeReviewPage.ID, "Code Review", 50, CodeReviewPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                GitPage.ID, "Git", 60, GitPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                BuildPage.ID, "Build", 70, BuildPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                TestQualityPage.ID, "Tests & Quality", 80, TestQualityPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                AiIntegrationPage.ID, "AI Integration", 90, AiIntegrationPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                McpPage.ID, "MCP", 100, McpPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                TeipIntegrationPage.ID, "TEIP Integration", 110, TeipIntegrationPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                ServerMonitoringPage.ID, "Server Monitoring", 120, ServerMonitoringPage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                DatabasePage.ID, "Database", 125, DatabasePage::new));

        reg.registerPage(new TrystEditorPageDescriptor(
                ConfigurationPage.ID, "Configuration", 130, ConfigurationPage::new));
    }
}
