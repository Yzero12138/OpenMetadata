# PC form drawers

The audited modal-hosted create, edit, configuration, import/export and assignment surfaces below open from the right. Their existing fields, validation, callbacks, widths and test IDs remain at their original call sites. Full-page route-hosted creation/editing wizards retain their routes. The hospital integration task editor is handled separately by its page implementation.

`FormDrawer` uses the native UI core `SlideoutMenu`. Its header and footer stay fixed while the body scrolls. It provides a localized close control, accessible title, keyboard/focus management and return focus through React Aria. Closing/reopening retains mounted drafts unless the caller requests `destroyOnClose`. A pending save disables close, Escape, backdrop dismissal, footer actions and repeated native form submissions. Both native and Ant Design selection popups render inside the drawer so they remain above legacy drawers without escaping the focus scope.

New forms can import `components/common/atoms/drawer/FormDrawer` and supply `isOpen`, `title`, `onClose`, optional `width`/`isSubmitting`/`footer`, and children. Footer submit buttons should keep their native `form` association. `FormDrawerActions` moves existing nested actions into the fixed footer; outside this drawer it preserves their original position. Explicit `LegacyFormDrawer` and `CoreFormDrawer` adapters preserve the audited callers' prop contracts. Neither Ant Design Modal nor native Dialog is changed globally.

## Migrated inventory

Paths below are relative to `openmetadata-ui/src/main/resources/ui/src/`. A source can contain multiple related form variants.

| Area | Migrated source(s) |
| --- | --- |
| Rich text links | `components/BlockEditor/LinkModal/LinkModal.tsx` |
| Table custom properties | `components/common/CustomPropertyTable/TableTypeProperty/EditTableTypePropertyModal.tsx` |
| Widget tabs | `components/Customization/CustomizeTabWidget/CustomizeTabWidget.tsx` (two form dialogs) |
| Profiler settings | `components/Database/Profiler/ProfilerSettings/ProfilerSettings.tsx` |
| Custom metrics | `components/Database/Profiler/TableProfiler/CustomMetricGraphs/CustomMetricGraphs.component.tsx` |
| Retention | `components/Database/RetentionPeriod/RetentionPeriod.component.tsx` |
| Incident severity | `components/DataQuality/IncidentManager/Severity/SeverityModal.component.tsx` |
| Incident SQL | `components/DataQuality/IncidentManager/SqlQueryTab/AddSqlQueryFormModal/AddSqlQueryFormModal.component.tsx` |
| Test-case status | `components/DataQuality/TestCaseStatusModal/TestCaseStatusModal.component.tsx` |
| Pipeline assignment | `components/Entity/EntityLineage/AppPipelineModel/AddPipeLineModal.tsx` |
| Lineage configuration | `components/Entity/EntityLineage/LineageConfigModal.tsx` |
| Tasks | `components/Entity/Task/TaskTab/TaskTabNew.component.tsx` (three form variants) |
| Advanced search | `components/Explore/AdvanceSearchModal.component.tsx` |
| Search export | `components/ExploreV1/ExploreV1.component.tsx` |
| Glossary term creation | `components/Glossary/GlossaryTermModal/GlossaryTermModal.component.tsx` |
| Glossary references | `components/Glossary/GlossaryTerms/GlossaryTermReferencesModal.component.tsx` |
| Announcements | `components/Modals/AnnouncementModal/AddAnnouncementModal.tsx`, `EditAnnouncementModal.tsx` |
| Parent hierarchy | `components/Modals/ChangeParentHierarchy/ChangeParentHierarchy.component.tsx` |
| Custom property editor | `components/Modals/ModalWithCustomProperty/ModalWithCustomPropertyEditor.component.tsx` |
| Function editor | `components/Modals/ModalWithFunctionEditor/ModalWithFunctionEditor.tsx` |
| Markdown editor | `components/Modals/ModalWithMarkdownEditor/ModalWithMarkdownEditor.tsx` |
| Query editor | `components/Modals/ModalWithQueryEditor/ModalWithQueryEditor.tsx` |
| Profile editing | `components/Modals/ProfileEditModal/ProfileEditModal.tsx` |
| Entity style | `components/Modals/StyleModal/StyleModal.component.tsx` |
| Detail-page widgets | `components/MyData/CustomizableComponents/AddDetailsPageWidgetModal/AddDetailsPageWidgetModal.tsx` |
| Home widgets | `components/MyData/CustomizableComponents/AddWidgetModal/AddWidgetModal.tsx` |
| Home customization | `components/MyData/CustomizableComponents/CustomiseHomeModal/CustomiseHomeModal.tsx` |
| Persona editing | `components/MyData/Persona/AddEditPersona/AddEditPersona.component.tsx` |
| Curated assets | `components/MyData/Widgets/CuratedAssetsWidget/CuratedAssetsModal/CuratedAssetsModal.tsx` |
| Search boosts | `components/SearchSettings/FieldValueBoostModal/FieldValueBoostModal.tsx` |
| Application schedule | `components/Settings/Applications/AppSchedule/AppSchedule.component.tsx` |
| Custom-property settings | `components/Settings/CustomProperty/EditCustomPropertyModal/EditCustomPropertyModal.tsx` |
| Email test form | `components/Settings/Email/TestEmail/TestEmail.component.tsx` (UI container only) |
| Team subscriptions | `components/Settings/Team/TeamDetails/TeamsHeaderSection/TeamsSubscription.component.tsx` |
| Password form | `components/Settings/Users/ChangePasswordForm.tsx` (UI container only; authentication unchanged) |
| Audit export | `pages/AuditLogsPage/AuditLogsPage.tsx` |
| Role attributes | `pages/RolesPage/AddAttributeModal/AddAttributeModal.tsx` |
| Table constraints | `pages/TableDetailsPageV1/TableConstraints/TableConstraintsModal/TableConstraintsModal.component.tsx` |
| Team creation | `pages/TeamsPage/AddTeamForm.tsx` |
| Folder creation | `components/ContextCenter/CreateFolderModal/CreateFolderModal.component.tsx` |
| Context memory | `components/ContextCenter/CreateMemoryModal/CreateMemoryModal.component.tsx` |
| Document upload | `components/ContextCenter/UploadDocumentModal/UploadDocumentModal.component.tsx` |
| Asset selection | `components/DataAssets/AssetsSelectionModal/AssetSelectionModal.tsx` |
| ODCS import | `components/DataContract/ODCSImportModal/ODCSImportModal.component.tsx` |
| Data-product metadata | `components/DataProducts/DataProductMetadataModal/DataProductMetadataModal.component.tsx` |
| ODPS import | `components/DataProducts/ODPSImportModal/ODPSImportModal.component.tsx` |
| Bundle-suite assignment | `components/DataQuality/AddToBundleSuiteModal/AddToBundleSuiteModal.component.tsx` |
| Entity export | `components/Entity/EntityExportModalProvider/EntityExportModalProvider.component.tsx` |
| Ontology import | `components/Glossary/ImportOntologyModal/ImportOntologyModal.component.tsx` |
| Quick links | `components/KnowledgeCenter/QuickLinkFormModal/QuickLinkFormModal.tsx` |
| Entity rename | `components/Modals/EntityNameModal/EntityNameModal.component.tsx` |
| Icon/color editing | `components/Modals/IconColorModal/IconColorModal.tsx` |
| Workflow display name | `components/WorkflowDefinitions/WorkflowBuilder/WorkflowHeader.tsx` |
| Test-suite assignment | `pages/TestSuiteDetailsPage/TestSuiteDetailsPage.component.tsx` |
| Data-asset rules | `components/DataAssetRules/DataAssetRules.component.tsx` (add/edit only) |
| Schema editing | `components/Modals/SchemaModal/SchemaModal.tsx` (footer-enabled editor only) |
| Data-quality form shell | `components/common/atoms/drawer/AiFormModal.tsx`: test-case create/edit, bundle-suite forms and the modal variant of test-definition forms inherit the right drawer |

The inventory contains 58 migrated container sources: 40 legacy adapters, 15 core adapters, two mixed-purpose sources and the shared data-quality shell. Existing already-right-sided drawers remain native. `AddTestCaseList` only changes the location of its action area when hosted in `FormDrawer`; standalone usage remains supported.

## Deliberate exclusions

The remaining centered surfaces were reviewed by their content, rather than their filenames:

- Delete/restore/remove, discard-unsaved, logout, stop/kill-run and move/status/domain/default-persona confirmation dialogs remain confirmations. This includes `WorkflowControls`, `ConfirmationModal`, `DeleteModal`, `DeleteEntityModal`, `UnsavedChangesModal`, `NavigationGuardModal`, `KillIngestionPipelineModal`, `StopScheduleRun`, lineage edge deletion, hierarchy drag/move confirmations, team/user/role/policy removals and the delete branch of data-asset rules.
- Log viewers, connection-test results, domain dry-run output, bulk-import version summaries, learning media, ingestion deployment/run progress, tour acknowledgement, CSV column-reference help, read-only application configuration, persona context preview and SSO test status remain viewers/status surfaces.
- `SchemaModal` retains its centered read-only path for row-data inspection; only its footer-enabled editing path moves right. Persona version-history restore remains a confirmation within its existing right drawer.
- Inline table popovers using React Aria `Dialog` are not modal-hosted forms. Existing full-page service/entity creation wizards and routed editors preserve their routes.

## Verification

`FormDrawer.test.tsx` exercises the actual native slideout, Ant Design form submission, focus return, retained draft state, pending-close protection, nested footer actions and native Select portal containment. Representative existing form suites cover folder creation, retention, glossary references, workflow headers and data-quality test cases. Pending state is wired to each caller's existing save/import/export state; synchronous configuration callbacks keep their existing behavior.

Explicit native `getContainer` callbacks are forwarded through the legacy adapter to a portal provider around the slideout itself. Omitted containers inherit the existing native provider. The link editor keeps its editor-dialog host; the real nested-dialog regression verifies mounting, input focus, one form submission and focus return after the editor closes the link form. The link and shared-drawer suites pass 14 tests.

`AddTestCaseList` reports assignment submission through its optional `onSubmittingChange` callback. The test-suite host connects it to the drawer and guards external close requests; standalone usage also disables cancel and repeated submissions until completion. List fetching has a separate loading state. Announcement editing accepts and awaits asynchronous confirmation, retains its draft on failure and restores save/close controls for retry.

The pending-state follow-up passed the three affected suites: assignment list (50 tests), test-suite host (40 tests) and announcement editing (7 tests). New regressions were observed failing before their fixes and cover pending dismissal/re-entry, failure recovery and standalone behavior.

The focused and direct-caller compatibility runs cover 18 suites and 287 tests. All suites passed after updating stale centered-container mocks and localized close-button queries. The shared drawer/adapters/Ai shell also pass an isolated TypeScript check. The full application TypeScript check still reports existing errors; the migrated caller files retain four existing diagnostics in the context-memory field descriptors and workflow-task schema code. The required organize-imports, ESLint and Prettier sequence was completed for changed UI files; ESLint reports no errors and retains the existing audit-log polling declaration-order warning.

Visual inspection and the integrated UI build are part of the parent task's batched verification. This inventory does not assert that every routed form in the application was converted to a popup.
