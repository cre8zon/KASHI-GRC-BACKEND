-- ═══════════════════════════════════════════════════════════════════════════
-- 01 — Requirement text for all 134 UCF leaf controls
--
-- REPLACES the earlier backfill script, which sourced text from
-- audit_controls.description. You said those are being rewritten, so sourcing
-- from them would have imported text that is about to change. These are
-- authored fresh against each control's title, family and framework citations.
--
-- WHY THIS COLUMN SPECIFICALLY
--
-- common_controls.description is what PolicyContextAssembler.buildControlBlock()
-- renders into every generation prompt as "Requirement:". It is currently NULL
-- on all 134 leaf controls, so the model is grounded on titles alone:
--
--     [IAM-01.1] Joiner provisioning and approval
--
-- which yields "access shall be provisioned appropriately". With the text
-- below it receives the actual obligation and yields a clause naming the
-- approval step, the approver and the retained record. No prompt tuning
-- substitutes for this, because the information is not in the prompt.
--
-- HOUSE STYLE (keep it if you edit these)
--   - states WHO does WHAT, and at what FREQUENCY where one applies
--   - names the evidence an assessor would ask for
--   - framework-neutral: no ISO, AICPA or DPDP wording is reproduced. These
--     are original summaries. Clause identifiers live in
--     common_control_mappings.citation, which is where they belong — the
--     identifiers are not protected, the requirement text of ISO 27001 and the
--     SOC 2 TSC is.
--   - present tense, active voice, British spelling, 19-33 words
--
-- REVIEW BEFORE RUNNING. These are a strong first draft, not gospel. Where a
-- description states a frequency or SLA generically ("at a defined interval"),
-- that is deliberate — the UCF is framework-neutral and the actual interval
-- belongs on the tenant's policy, not on the shared catalogue row.
-- ═══════════════════════════════════════════════════════════════════════════

START TRANSACTION;


-- ── GOV — Governance, Risk & Compliance ─────────────────────────────────────
UPDATE common_controls SET description = 'A documented set of information security policies is approved by management, published to all personnel, and covers the topics relevant to the organisation''s risk profile and obligations.', updated_at = NOW(6) WHERE code = 'GOV-01.1';

-- ── HRS — Human Resources Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Background verification appropriate to the role and applicable law is completed for personnel and relevant contractors before they are granted access to systems or facilities, and results are recorded.', updated_at = NOW(6) WHERE code = 'HRS-01.1';

-- ── IAM — Identity & Access Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Access for new joiners is granted only after documented approval from an authorised approver, is based on the entitlements defined for the role, and the approval record is retained.', updated_at = NOW(6) WHERE code = 'IAM-01.1';

-- ── AST — Asset & Information Management ─────────────────────────────────────
UPDATE common_controls SET description = 'A complete inventory of hardware and software assets is maintained, updated as assets are added, changed or retired, and reconciled against discovery data at a defined interval.', updated_at = NOW(6) WHERE code = 'AST-01.1';

-- ── END — Endpoint Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Anti-malware or endpoint detection and response software is deployed to all in-scope endpoints and servers, kept current, and its coverage is measured against the asset inventory to identify gaps.', updated_at = NOW(6) WHERE code = 'END-01.1';

-- ── APP — Secure Development ─────────────────────────────────────
UPDATE common_controls SET description = 'A documented secure development lifecycle defines the security activities required at each stage from design through release, and applies to all in-scope applications and teams.', updated_at = NOW(6) WHERE code = 'APP-01.1';

-- ── CRY — Cryptography & Key Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Data at rest in databases, object storage, backups and endpoints is encrypted using an approved algorithm, with coverage verified across all stores holding classified or personal data.', updated_at = NOW(6) WHERE code = 'CRY-01.1';

-- ── NET — Network Security ─────────────────────────────────────
UPDATE common_controls SET description = 'The network is segmented so that systems of differing trust levels and data sensitivity are separated, with traffic between segments restricted to explicitly permitted flows.', updated_at = NOW(6) WHERE code = 'NET-01.1';

-- ── TPR — Third-Party Risk Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Vendors are assessed for security and privacy risk before onboarding, with the depth of assessment proportionate to the data and access involved, and the assessment outcome recorded and approved.', updated_at = NOW(6) WHERE code = 'TPR-01.1';

-- ── PHY — Physical & Environmental Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Physical security perimeters protecting areas containing information systems are defined and physically sound, with entry points controlled and the perimeter maintained.', updated_at = NOW(6) WHERE code = 'PHY-01.1';

-- ── PRI — Privacy & Data Protection ─────────────────────────────────────
UPDATE common_controls SET description = 'A privacy notice is provided to data principals before or at the point of collection, in clear language and in the required languages, describing the data collected, the purposes and their rights.', updated_at = NOW(6) WHERE code = 'PRI-01.1';

-- ── BCP — Continuity, Backup & Resilience ─────────────────────────────────────
UPDATE common_controls SET description = 'Backups cover all systems and data required to restore business operations, run at a frequency consistent with the defined recovery point objective, and their success or failure is monitored and alerted.', updated_at = NOW(6) WHERE code = 'BCP-01.1';

-- ── IRP — Incident Response ─────────────────────────────────────
UPDATE common_controls SET description = 'A documented incident response plan defines roles, severity levels, escalation paths, communication responsibilities and evidence handling, and is accessible to responders when primary systems are unavailable.', updated_at = NOW(6) WHERE code = 'IRP-01.1';

-- ── VUL — Vulnerability & Patch Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Vulnerability scanning covers in-scope infrastructure, endpoints and applications at a defined cadence and after significant change, with scan coverage reconciled against the asset inventory.', updated_at = NOW(6) WHERE code = 'VUL-01.1';

-- ── LOG — Logging & Monitoring ─────────────────────────────────────
UPDATE common_controls SET description = 'Security-relevant events including authentication, authorisation changes, privileged actions and access to sensitive data are logged across in-scope systems, with the logging scope documented.', updated_at = NOW(6) WHERE code = 'LOG-01.1';

-- ── CHG — Change & Configuration Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Changes to production systems are requested, assessed for risk and security impact, and approved by an authorised approver other than the implementer before deployment, with approval recorded.', updated_at = NOW(6) WHERE code = 'CHG-01.1';
UPDATE common_controls SET description = 'Changes are tested before production deployment and have a documented rollback or recovery plan proportionate to their risk, with test evidence retained.', updated_at = NOW(6) WHERE code = 'CHG-01.2';

-- ── LOG — Logging & Monitoring ─────────────────────────────────────
UPDATE common_controls SET description = 'Logs are retained for a defined period that satisfies investigative, contractual and regulatory needs, with the retention period documented and enforced by the logging platform.', updated_at = NOW(6) WHERE code = 'LOG-01.2';

-- ── VUL — Vulnerability & Patch Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Remediation timeframes are defined by vulnerability severity, tracked from discovery to closure, and overdue items are escalated, with the SLA and current performance reported.', updated_at = NOW(6) WHERE code = 'VUL-01.2';

-- ── TPR — Third-Party Risk Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Vendors are assigned a risk tier using defined criteria such as data sensitivity, system access and business criticality, and the tier determines assessment depth and reassessment frequency.', updated_at = NOW(6) WHERE code = 'TPR-01.2';

-- ── PHY — Physical & Environmental Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Entry to secure areas is restricted to authorised individuals through a controlled mechanism, authorisation is approved and recorded, and access rights are reviewed at a defined interval.', updated_at = NOW(6) WHERE code = 'PHY-01.2';

-- ── PRI — Privacy & Data Protection ─────────────────────────────────────
UPDATE common_controls SET description = 'Consent is obtained through a free, specific, informed and unambiguous affirmative action where consent is the basis for processing, and withdrawal is made as easy as giving consent.', updated_at = NOW(6) WHERE code = 'PRI-01.2';

-- ── BCP — Continuity, Backup & Resilience ─────────────────────────────────────
UPDATE common_controls SET description = 'Restoration from backup is tested at a defined interval by actually recovering data, not merely verifying the backup completed, and the test outcome and any failures are recorded.', updated_at = NOW(6) WHERE code = 'BCP-01.2';

-- ── IRP — Incident Response ─────────────────────────────────────
UPDATE common_controls SET description = 'Reported events are classified against defined severity criteria and triaged within a timeframe appropriate to that severity, with the classification and rationale recorded.', updated_at = NOW(6) WHERE code = 'IRP-01.2';

-- ── NET — Network Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Firewall and security group rulesets are reviewed at a defined interval to confirm each rule has a business justification and an owner, with unjustified and overly permissive rules removed.', updated_at = NOW(6) WHERE code = 'NET-01.2';

-- ── CRY — Cryptography & Key Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Data in transit across untrusted networks is protected using current TLS versions with approved cipher suites, with plaintext protocols disabled and configuration verified periodically.', updated_at = NOW(6) WHERE code = 'CRY-01.2';

-- ── APP — Secure Development ─────────────────────────────────────
UPDATE common_controls SET description = 'Code changes are reviewed and approved by a competent person other than the author before merging to a protected branch, with the review record retained.', updated_at = NOW(6) WHERE code = 'APP-01.2';

-- ── END — Endpoint Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Endpoints are configured to a documented hardening baseline covering security settings, disabled services and local administrative rights, with compliance monitored and deviations remediated.', updated_at = NOW(6) WHERE code = 'END-01.2';

-- ── AST — Asset & Information Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Information assets and the systems that store or process them are recorded in a register capturing the data held, its classification, its location and the business process it supports.', updated_at = NOW(6) WHERE code = 'AST-01.2';

-- ── IAM — Identity & Access Management ─────────────────────────────────────
UPDATE common_controls SET description = 'When a person changes role, their entitlements are reassessed and adjusted within a defined period so that access no longer required by the new role is removed rather than accumulated.', updated_at = NOW(6) WHERE code = 'IAM-01.2';

-- ── HRS — Human Resources Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Employment and contractor agreements state information security and confidentiality responsibilities, including obligations that survive the end of the engagement, and are executed before access is granted.', updated_at = NOW(6) WHERE code = 'HRS-01.2';

-- ── GOV — Governance, Risk & Compliance ─────────────────────────────────────
UPDATE common_controls SET description = 'Each policy names an owner and is formally reviewed and re-approved at a defined interval, and additionally whenever a significant change to the business, technology estate or regulatory obligations occurs.', updated_at = NOW(6) WHERE code = 'GOV-01.2';
UPDATE common_controls SET description = 'Information security roles, responsibilities and decision rights are defined, assigned to named individuals or roles, and communicated so accountability for each control area is unambiguous.', updated_at = NOW(6) WHERE code = 'GOV-01.3';

-- ── AST — Asset & Information Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Every asset in the inventory has a named owner accountable for its classification, protection and permitted use, and ownership is reassigned when personnel or responsibilities change.', updated_at = NOW(6) WHERE code = 'AST-01.3';

-- ── IAM — Identity & Access Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Access for departing personnel and contractors is revoked across all systems within a defined service level from the termination trigger, and the revocation is evidenced.', updated_at = NOW(6) WHERE code = 'IAM-01.3';

-- ── CRY — Cryptography & Key Management ─────────────────────────────────────
UPDATE common_controls SET description = 'An approved cryptography standard defines permitted algorithms, minimum key lengths and cipher suites, prohibits deprecated primitives, and is reviewed as cryptographic guidance evolves.', updated_at = NOW(6) WHERE code = 'CRY-01.3';

-- ── NET — Network Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Controls protect the network boundary against unauthorised access and volumetric attack, including ingress filtering and denial-of-service mitigation appropriate to the exposure of internet-facing services.', updated_at = NOW(6) WHERE code = 'NET-01.3';

-- ── APP — Secure Development ─────────────────────────────────────
UPDATE common_controls SET description = 'Protected branches prevent direct commits, require review approval and passing checks before merge, and restrict who may override these controls, with overrides logged.', updated_at = NOW(6) WHERE code = 'APP-01.3';

-- ── END — Endpoint Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Full-disk encryption is enabled on laptops and other portable endpoints, with enrolment enforced centrally and coverage reported against the device inventory.', updated_at = NOW(6) WHERE code = 'END-01.3';

-- ── PRI — Privacy & Data Protection ─────────────────────────────────────
UPDATE common_controls SET description = 'A record is kept of each consent showing what was consented to, the notice presented at the time, the timestamp, and any subsequent withdrawal, sufficient to demonstrate valid consent.', updated_at = NOW(6) WHERE code = 'PRI-01.3';

-- ── IRP — Incident Response ─────────────────────────────────────
UPDATE common_controls SET description = 'The incident response plan is exercised at a defined interval using a realistic scenario, with participation from the roles named in the plan, and lessons learned fed back into the plan.', updated_at = NOW(6) WHERE code = 'IRP-01.3';

-- ── CHG — Change & Configuration Management ─────────────────────────────────────
UPDATE common_controls SET description = 'A defined emergency change procedure permits expedited deployment when justified, and requires retrospective review and approval within a specified period after the event.', updated_at = NOW(6) WHERE code = 'CHG-01.3';

-- ── VUL — Vulnerability & Patch Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Threat and vulnerability intelligence relevant to the organisation''s technology and sector is received from defined sources, assessed for applicability, and acted upon where relevant.', updated_at = NOW(6) WHERE code = 'VUL-01.3';

-- ── LOG — Logging & Monitoring ─────────────────────────────────────
UPDATE common_controls SET description = 'Logs are protected against modification and deletion, including by privileged users, through centralisation to a separate system, restricted access, and integrity controls.', updated_at = NOW(6) WHERE code = 'LOG-01.3';

-- ── PHY — Physical & Environmental Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Visitors are identified, authorised, recorded on entry and exit, issued visible identification, and escorted while in secure areas, with the visitor record retained.', updated_at = NOW(6) WHERE code = 'PHY-01.3';

-- ── BCP — Continuity, Backup & Resilience ─────────────────────────────────────
UPDATE common_controls SET description = 'Backup data is encrypted and at least one copy is held in a location or account separate from the primary environment, so that a single compromise or failure cannot destroy both.', updated_at = NOW(6) WHERE code = 'BCP-01.3';

-- ── PHY — Physical & Environmental Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Premises and secure areas are monitored for unauthorised physical access using surveillance or intrusion detection appropriate to the risk, with recordings retained for a defined period.', updated_at = NOW(6) WHERE code = 'PHY-01.4';

-- ── IRP — Incident Response ─────────────────────────────────────
UPDATE common_controls SET description = 'Incidents above a defined severity receive a documented post-incident review identifying root cause and corrective actions, with those actions assigned owners and tracked to completion.', updated_at = NOW(6) WHERE code = 'IRP-01.4';

-- ── GOV — Governance, Risk & Compliance ─────────────────────────────────────
UPDATE common_controls SET description = 'Management formally reviews the information security programme at a planned interval, considering performance results, audit findings, incidents, risk changes and improvement opportunities, and records the decisions taken.', updated_at = NOW(6) WHERE code = 'GOV-01.4';

-- ── IAM — Identity & Access Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Each user is assigned a unique identifier that attributes their actions to them individually; shared, generic and service accounts are inventoried, justified, owned and their credentials controlled.', updated_at = NOW(6) WHERE code = 'IAM-01.4';

-- ── APP — Secure Development ─────────────────────────────────────
UPDATE common_controls SET description = 'Secure coding standards addressing common vulnerability classes are documented, made available to developers, and reinforced through training and code review criteria.', updated_at = NOW(6) WHERE code = 'APP-01.4';

-- ── END — Endpoint Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Devices accessing organisational data are enrolled in a management solution that enforces the required security configuration and enables remote lock or wipe of organisational data.', updated_at = NOW(6) WHERE code = 'END-01.4';

-- ── LOG — Logging & Monitoring ─────────────────────────────────────
UPDATE common_controls SET description = 'Actions performed using administrative and privileged accounts are logged in sufficient detail to attribute each action to an individual, and these logs are reviewed or alerted on.', updated_at = NOW(6) WHERE code = 'LOG-01.4';

-- ── PRI — Privacy & Data Protection ─────────────────────────────────────
UPDATE common_controls SET description = 'Where the data principal is a child or a person with a disability having a lawful guardian, verifiable consent is obtained from the parent or guardian before processing, and prohibited processing is prevented.', updated_at = NOW(6) WHERE code = 'PRI-01.4';

-- ── PHY — Physical & Environmental Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Offices, rooms and facilities housing information assets are designed and secured to prevent unauthorised access and to avoid publicly signalling the presence of sensitive operations.', updated_at = NOW(6) WHERE code = 'PHY-01.5';

-- ── GOV — Governance, Risk & Compliance ─────────────────────────────────────
UPDATE common_controls SET description = 'Measurable information security objectives are established, aligned to the security policy, assigned to owners with target dates, and progress against them is monitored and reported.', updated_at = NOW(6) WHERE code = 'GOV-01.5';
UPDATE common_controls SET description = 'A code of conduct setting expected ethical and behavioural standards is issued to all personnel, who formally acknowledge it on joining and at a defined recurring interval thereafter.', updated_at = NOW(6) WHERE code = 'GOV-01.6';

-- ── NET — Network Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Remote access to the internal environment is granted only to authorised users over an encrypted channel, from managed or attested endpoints where feasible, and remote sessions are logged.', updated_at = NOW(6) WHERE code = 'NET-02.1';

-- ── CHG — Change & Configuration Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Secure configuration baselines are defined for each system and platform type, applied at build, and maintained as the authoritative definition of an acceptable configuration.', updated_at = NOW(6) WHERE code = 'CHG-02.1';

-- ── APP — Secure Development ─────────────────────────────────────
UPDATE common_controls SET description = 'Static and dynamic application security testing runs within the delivery pipeline on a defined trigger, with findings routed to owners and severity thresholds defined for blocking a release.', updated_at = NOW(6) WHERE code = 'APP-02.1';

-- ── IAM — Identity & Access Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Authentication credential requirements covering minimum length, complexity or passphrase rules, reuse restrictions and handling of compromised credentials are defined and technically enforced.', updated_at = NOW(6) WHERE code = 'IAM-02.1';

-- ── CRY — Cryptography & Key Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Cryptographic keys are generated using an approved method, stored in a dedicated key management service or hardware module, and access to key material is restricted to authorised custodians and logged.', updated_at = NOW(6) WHERE code = 'CRY-02.1';

-- ── VUL — Vulnerability & Patch Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Security patches are deployed to systems and applications within timeframes defined by severity, with deployment coverage measured and unpatched systems identified and escalated.', updated_at = NOW(6) WHERE code = 'VUL-02.1';

-- ── LOG — Logging & Monitoring ─────────────────────────────────────
UPDATE common_controls SET description = 'Security events are monitored against defined detection rules that generate alerts for suspicious activity, with rule coverage reviewed as the threat landscape and environment change.', updated_at = NOW(6) WHERE code = 'LOG-02.1';

-- ── PRI — Privacy & Data Protection ─────────────────────────────────────
UPDATE common_controls SET description = 'Requests from data principals for access to a summary of their personal data and its processing are fulfilled within the required timeframe through a defined and evidenced process.', updated_at = NOW(6) WHERE code = 'PRI-02.1';

-- ── TPR — Third-Party Risk Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Agreements with vendors include security requirements covering confidentiality, incident notification, subcontracting, audit rights and return or deletion of data at termination.', updated_at = NOW(6) WHERE code = 'TPR-02.1';

-- ── PHY — Physical & Environmental Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Supporting utilities and environmental protections including power continuity, cooling and fire detection and suppression are provided appropriate to the facility, and maintained and tested.', updated_at = NOW(6) WHERE code = 'PHY-02.1';

-- ── IRP — Incident Response ─────────────────────────────────────
UPDATE common_controls SET description = 'Regulatory breach notification obligations are documented with their applicable timeframes and recipients, and the process for assessing notifiability and issuing notice is defined and rehearsed.', updated_at = NOW(6) WHERE code = 'IRP-02.1';

-- ── BCP — Continuity, Backup & Resilience ─────────────────────────────────────
UPDATE common_controls SET description = 'A business continuity plan identifies critical business processes, their dependencies and the arrangements for continuing or recovering them, and names the individuals responsible for invoking it.', updated_at = NOW(6) WHERE code = 'BCP-02.1';

-- ── AST — Asset & Information Management ─────────────────────────────────────
UPDATE common_controls SET description = 'A data classification scheme defines the classification levels in use, the criteria for assigning each, and the minimum handling requirements that apply at each level.', updated_at = NOW(6) WHERE code = 'AST-02.1';

-- ── GOV — Governance, Risk & Compliance ─────────────────────────────────────
UPDATE common_controls SET description = 'A documented risk assessment methodology defines how risks are identified, how likelihood and impact are scored, the criteria for accepting risk, and how often assessments are performed.', updated_at = NOW(6) WHERE code = 'GOV-02.1';

-- ── HRS — Human Resources Security ─────────────────────────────────────
UPDATE common_controls SET description = 'All personnel complete security awareness training on joining and at a defined recurring interval, with content covering the organisation''s policies and current threats, and completion tracked to full coverage.', updated_at = NOW(6) WHERE code = 'HRS-02.1';

-- ── PHY — Physical & Environmental Security ─────────────────────────────────────
UPDATE common_controls SET description = 'A clear desk and clear screen requirement is defined and communicated, requiring sensitive material to be secured when unattended and screens to lock when the user is away.', updated_at = NOW(6) WHERE code = 'PHY-02.2';

-- ── PRI — Privacy & Data Protection ─────────────────────────────────────
UPDATE common_controls SET description = 'Requests for correction, completion, updating or erasure of personal data are actioned within the required timeframe, including onward communication to recipients where applicable.', updated_at = NOW(6) WHERE code = 'PRI-02.2';

-- ── TPR — Third-Party Risk Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Where a vendor processes personal data, a data processing agreement is executed defining the permitted purposes, security obligations, subprocessor rules and assistance with data principal rights.', updated_at = NOW(6) WHERE code = 'TPR-02.2';

-- ── GOV — Governance, Risk & Compliance ─────────────────────────────────────
UPDATE common_controls SET description = 'Identified risks are recorded in a maintained register with an owner, current score, selected treatment and target date, and treatment progress is tracked to closure.', updated_at = NOW(6) WHERE code = 'GOV-02.2';

-- ── CRY — Cryptography & Key Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Keys are rotated at a defined interval and on compromise or custodian departure, and revoked keys are retired from use, with rotation and revocation events recorded.', updated_at = NOW(6) WHERE code = 'CRY-02.2';

-- ── VUL — Vulnerability & Patch Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Where a patch cannot be applied within the defined timeframe, the exception is documented with a compensating control, approved by an authorised owner, given an expiry date and reviewed.', updated_at = NOW(6) WHERE code = 'VUL-02.2';

-- ── LOG — Logging & Monitoring ─────────────────────────────────────
UPDATE common_controls SET description = 'Alerts are triaged within a defined timeframe according to severity, escalated through a documented path, and their disposition recorded so that unactioned alerts are visible.', updated_at = NOW(6) WHERE code = 'LOG-02.2';

-- ── CHG — Change & Configuration Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Deviation from approved configuration baselines is detected through automated comparison at a defined frequency, with drift investigated and either remediated or formally accepted.', updated_at = NOW(6) WHERE code = 'CHG-02.2';

-- ── NET — Network Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Wireless networks use current authentication and encryption standards, guest access is segregated from the corporate network, and access credentials are managed and rotated.', updated_at = NOW(6) WHERE code = 'NET-02.2';

-- ── IAM — Identity & Access Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Multi-factor authentication is enforced for workforce access to systems holding or providing access to organisational data, with coverage monitored and exceptions formally approved and time-limited.', updated_at = NOW(6) WHERE code = 'IAM-02.2';

-- ── AST — Asset & Information Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Procedures define how information at each classification level is labelled, stored, transmitted, shared and disposed of, covering both electronic and physical form, and are communicated to personnel.', updated_at = NOW(6) WHERE code = 'AST-02.2';

-- ── HRS — Human Resources Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Personnel formally acknowledge the acceptable use policy for organisational systems, data and devices on joining and at a defined recurring interval, and acknowledgement records are retained.', updated_at = NOW(6) WHERE code = 'HRS-02.2';

-- ── BCP — Continuity, Backup & Resilience ─────────────────────────────────────
UPDATE common_controls SET description = 'Disaster recovery arrangements are exercised at a defined interval against a realistic scenario, with the outcome measured against the stated recovery objectives and shortfalls remediated.', updated_at = NOW(6) WHERE code = 'BCP-02.2';

-- ── APP — Secure Development ─────────────────────────────────────
UPDATE common_controls SET description = 'Third-party and open-source dependencies are scanned for known vulnerabilities and licence issues on a defined cadence, with vulnerable components upgraded or mitigated within the remediation SLA.', updated_at = NOW(6) WHERE code = 'APP-02.2';

-- ── IRP — Incident Response ─────────────────────────────────────
UPDATE common_controls SET description = 'Affected customers and data principals are notified of a breach affecting their data within the applicable timeframe, using defined content covering the nature of the breach and the steps they should take.', updated_at = NOW(6) WHERE code = 'IRP-02.2';

-- ── GOV — Governance, Risk & Compliance ─────────────────────────────────────
UPDATE common_controls SET description = 'Risks accepted rather than treated, and exceptions to security requirements, are formally approved by an authorised owner, given an expiry date, and reviewed before that date.', updated_at = NOW(6) WHERE code = 'GOV-02.3';

-- ── LOG — Logging & Monitoring ─────────────────────────────────────
UPDATE common_controls SET description = 'System clocks across in-scope systems are synchronised to an authoritative time source so that log timestamps can be correlated reliably during investigation.', updated_at = NOW(6) WHERE code = 'LOG-02.3';

-- ── CRY — Cryptography & Key Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Digital certificates are inventoried with their expiry dates, renewed before expiry through a defined process, and revoked when superseded or compromised, with expiry monitored and alerted.', updated_at = NOW(6) WHERE code = 'CRY-02.3';

-- ── APP — Secure Development ─────────────────────────────────────
UPDATE common_controls SET description = 'Penetration testing of in-scope applications and infrastructure is performed by a suitably independent and competent tester at a defined interval and after significant change, with findings tracked to closure.', updated_at = NOW(6) WHERE code = 'APP-02.3';

-- ── PRI — Privacy & Data Protection ─────────────────────────────────────
UPDATE common_controls SET description = 'A grievance redressal mechanism is published and readily available to data principals, with responses provided within the required timeframe and complaints logged and tracked to resolution.', updated_at = NOW(6) WHERE code = 'PRI-02.3';

-- ── BCP — Continuity, Backup & Resilience ─────────────────────────────────────
UPDATE common_controls SET description = 'Recovery time and recovery point objectives are defined for each critical system based on business impact, agreed with the business, and validated through testing rather than assumed.', updated_at = NOW(6) WHERE code = 'BCP-02.3';

-- ── HRS — Human Resources Security ─────────────────────────────────────
UPDATE common_controls SET description = 'A documented disciplinary process defines the consequences of information security policy violations, is communicated to personnel, and is applied consistently following a substantiated breach.', updated_at = NOW(6) WHERE code = 'HRS-02.3';

-- ── AST — Asset & Information Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Use of removable media is governed by a documented rule set covering authorisation, encryption, permitted data classifications and secure disposal, with unauthorised use technically prevented where feasible.', updated_at = NOW(6) WHERE code = 'AST-02.3';

-- ── IAM — Identity & Access Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Multi-factor authentication is enforced for all privileged and administrative access without exception, and enforcement is verified rather than assumed.', updated_at = NOW(6) WHERE code = 'IAM-02.3';

-- ── GOV — Governance, Risk & Compliance ─────────────────────────────────────
UPDATE common_controls SET description = 'A risk treatment plan records the controls selected to address assessed risks, and a Statement of Applicability records which controls apply, the justification for inclusion or exclusion, and their implementation status.', updated_at = NOW(6) WHERE code = 'GOV-02.4';

-- ── IAM — Identity & Access Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Multi-factor authentication is enforced for remote and VPN access to the internal environment, including access from unmanaged networks and third-party connections.', updated_at = NOW(6) WHERE code = 'IAM-02.4';

-- ── BCP — Continuity, Backup & Resilience ─────────────────────────────────────
UPDATE common_controls SET description = 'Resource capacity is monitored against demand and projected growth, with thresholds that trigger action before availability is affected, and availability measured against any committed levels.', updated_at = NOW(6) WHERE code = 'BCP-02.4';

-- ── PRI — Privacy & Data Protection ─────────────────────────────────────
UPDATE common_controls SET description = 'Data principals are able to nominate another individual to exercise their rights in the event of death or incapacity, and the nomination is recorded and honoured.', updated_at = NOW(6) WHERE code = 'PRI-02.4';

-- ── IAM — Identity & Access Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Sessions terminate or lock after a defined period of inactivity, and accounts lock after a defined number of consecutive failed authentication attempts, with both thresholds technically enforced.', updated_at = NOW(6) WHERE code = 'IAM-02.5';

-- ── TPR — Third-Party Risk Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Vendors are reassessed at an interval determined by their risk tier and on significant change, with evidence such as current attestation reports reviewed and findings tracked.', updated_at = NOW(6) WHERE code = 'TPR-03.1';

-- ── PRI — Privacy & Data Protection ─────────────────────────────────────
UPDATE common_controls SET description = 'A Data Protection Officer or equivalent responsible person is appointed where required, their contact details are published to data principals, and they report to the organisation''s governing body.', updated_at = NOW(6) WHERE code = 'PRI-03.1';

-- ── APP — Secure Development ─────────────────────────────────────
UPDATE common_controls SET description = 'Development, test and production environments are logically or physically separated, with distinct credentials and access rights, and promotion between environments follows the change process.', updated_at = NOW(6) WHERE code = 'APP-03.1';

-- ── HRS — Human Resources Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Information security responsibilities that remain in force after employment ends, including confidentiality obligations, are defined, communicated to the individual and enforced after departure.', updated_at = NOW(6) WHERE code = 'HRS-03.1';

-- ── NET — Network Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Controls detect and restrict unauthorised transfer of classified or personal data out of the environment, covering the egress channels in scope, with alerts triaged and exceptions approved.', updated_at = NOW(6) WHERE code = 'NET-03.1';

-- ── IAM — Identity & Access Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Access rights are designed on the principle of least privilege, granting only the entitlements a role requires, with role definitions documented and reviewed when responsibilities change.', updated_at = NOW(6) WHERE code = 'IAM-03.1';

-- ── GOV — Governance, Risk & Compliance ─────────────────────────────────────
UPDATE common_controls SET description = 'Applicable legal, regulatory, contractual and statutory obligations relating to information security and privacy are identified, recorded in a register with a named owner, and reviewed for change.', updated_at = NOW(6) WHERE code = 'GOV-03.1';

-- ── AST — Asset & Information Management ─────────────────────────────────────
UPDATE common_controls SET description = 'A retention schedule defines how long each category of information is kept, the legal or business basis for that period, and the disposal action taken when the period expires.', updated_at = NOW(6) WHERE code = 'AST-03.1';

-- ── GOV — Governance, Risk & Compliance ─────────────────────────────────────
UPDATE common_controls SET description = 'Security controls are reviewed by a party independent of those who operate them, at a planned interval or after significant change, and findings are tracked to resolution.', updated_at = NOW(6) WHERE code = 'GOV-03.2';

-- ── AST — Asset & Information Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Information that has reached the end of its retention period or is no longer required is deleted using a method that prevents recovery, and the deletion is recorded.', updated_at = NOW(6) WHERE code = 'AST-03.2';

-- ── APP — Secure Development ─────────────────────────────────────
UPDATE common_controls SET description = 'Use of production data in non-production environments is prohibited or permitted only after masking, anonymisation or equivalent protection, with any exception formally approved and time-limited.', updated_at = NOW(6) WHERE code = 'APP-03.2';

-- ── TPR — Third-Party Risk Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Subprocessors and cloud services used by vendors are identified, their use approved, and changes to the subprocessor chain notified and assessed before taking effect.', updated_at = NOW(6) WHERE code = 'TPR-03.2';

-- ── HRS — Human Resources Security ─────────────────────────────────────
UPDATE common_controls SET description = 'All organisational assets issued to personnel, including devices, credentials, tokens and documents, are identified and returned or recovered as part of the exit process, and return is recorded.', updated_at = NOW(6) WHERE code = 'HRS-03.2';

-- ── IAM — Identity & Access Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Privileged accounts are inventoried, restricted to personnel with a demonstrated need, issued separately from standard accounts, and their use is logged and monitored.', updated_at = NOW(6) WHERE code = 'IAM-03.2';

-- ── NET — Network Security ─────────────────────────────────────
UPDATE common_controls SET description = 'Outbound web and content access is filtered against categories and reputation to reduce exposure to malicious and prohibited destinations, with the policy documented and bypasses controlled.', updated_at = NOW(6) WHERE code = 'NET-03.2';

-- ── PRI — Privacy & Data Protection ─────────────────────────────────────
UPDATE common_controls SET description = 'A data protection impact assessment is performed for processing likely to result in high risk to data principals, documenting the risks identified and the mitigations applied before processing begins.', updated_at = NOW(6) WHERE code = 'PRI-03.2';

-- ── GOV — Governance, Risk & Compliance ─────────────────────────────────────
UPDATE common_controls SET description = 'An internal audit programme covering the information security management system is planned, executed by competent and impartial auditors, and its findings reported to management and remediated.', updated_at = NOW(6) WHERE code = 'GOV-03.3';

-- ── IAM — Identity & Access Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Duties that could allow a single individual to initiate and conceal an unauthorised action are identified and separated between individuals, with compensating controls documented where separation is impractical.', updated_at = NOW(6) WHERE code = 'IAM-03.3';

-- ── AST — Asset & Information Management ─────────────────────────────────────
UPDATE common_controls SET description = 'Media and equipment are securely sanitised or physically destroyed before disposal, transfer or reuse, using a method appropriate to the classification of the data held, with a certificate or record retained.', updated_at = NOW(6) WHERE code = 'AST-03.3';

-- ── TPR — Third-Party Risk Management ─────────────────────────────────────
UPDATE common_controls SET description = 'At the end of a vendor relationship, access is revoked, organisational data is returned or destroyed, and confirmation of destruction is obtained and retained.', updated_at = NOW(6) WHERE code = 'TPR-03.3';

-- ── PRI — Privacy & Data Protection ─────────────────────────────────────
UPDATE common_controls SET description = 'A record of processing activities is maintained describing the categories of personal data, purposes, recipients, retention periods, transfers and security measures, and is kept current.', updated_at = NOW(6) WHERE code = 'PRI-03.3';
UPDATE common_controls SET description = 'Transfers of personal data outside the jurisdiction are identified, permitted only to destinations allowed by applicable law or contract, and supported by appropriate safeguards and records.', updated_at = NOW(6) WHERE code = 'PRI-03.4';
UPDATE common_controls SET description = 'Personal data is collected and processed only for specified lawful purposes and limited to what is necessary for those purposes, with collection reviewed to remove data no longer required.', updated_at = NOW(6) WHERE code = 'PRI-03.5';

-- ── GOV — Governance, Risk & Compliance ─────────────────────────────────────
UPDATE common_controls SET description = 'Internal and external issues relevant to the organisation''s purpose that affect its ability to achieve information security outcomes are determined, documented and reviewed for change.', updated_at = NOW(6) WHERE code = 'GOV-04.1';

-- ── IAM — Identity & Access Management ─────────────────────────────────────
UPDATE common_controls SET description = 'User access rights are reviewed by system or data owners at a defined interval, with the reviewer confirming each entitlement remains appropriate and removals actioned and evidenced.', updated_at = NOW(6) WHERE code = 'IAM-04.1';
UPDATE common_controls SET description = 'Privileged entitlements are recertified at a defined interval more frequent than standard access review, with each privileged account confirmed as still required or removed.', updated_at = NOW(6) WHERE code = 'IAM-04.2';

-- ── GOV — Governance, Risk & Compliance ─────────────────────────────────────
UPDATE common_controls SET description = 'Interested parties relevant to information security are identified together with their requirements, including legal, regulatory and contractual expectations, and these are kept current.', updated_at = NOW(6) WHERE code = 'GOV-04.2';
UPDATE common_controls SET description = 'The scope and boundaries of the information security management system are documented, including the locations, business units, systems and interfaces included and any justified exclusions.', updated_at = NOW(6) WHERE code = 'GOV-04.3';
UPDATE common_controls SET description = 'The resources, competencies and awareness needed to operate the information security programme are determined and provided, with competence evidenced by qualifications, training or experience records.', updated_at = NOW(6) WHERE code = 'GOV-04.4';
UPDATE common_controls SET description = 'Information security performance and control effectiveness are monitored using defined metrics, with the method, frequency and responsible party documented, and results retained as evidence.', updated_at = NOW(6) WHERE code = 'GOV-04.5';
UPDATE common_controls SET description = 'Information security performance results, including metric trends, incidents, audit findings and risk status, are reported to management at a defined interval in a documented form.', updated_at = NOW(6) WHERE code = 'GOV-04.6';
UPDATE common_controls SET description = 'The suitability, adequacy and effectiveness of the information security programme are continually improved, with improvement actions recorded, owned and tracked to completion.', updated_at = NOW(6) WHERE code = 'GOV-04.7';
UPDATE common_controls SET description = 'Nonconformities are recorded, their root cause determined, corrective action taken and its effectiveness verified, with the nature of the nonconformity and the outcome retained as evidence.', updated_at = NOW(6) WHERE code = 'GOV-04.8';

COMMIT;

-- ── Verify ──────────────────────────────────────────────────────────────────
SELECT node_level,
       COUNT(*)                                        AS total,
       SUM(description IS NOT NULL AND description<>'') AS with_description
  FROM common_controls
 GROUP BY node_level;
-- Expect CONTROL: 134 / 134.
