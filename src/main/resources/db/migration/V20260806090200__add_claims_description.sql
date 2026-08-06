alter table claims
    add column description text;

update claims set description = 'Follow-up visit for upper respiratory infection. Approved and paid in full ($245.00) on 2026-02-21.' where claim_reference = 'CLM-2026-000001';
update claims set description = 'Lumbar spine MRI for chronic back pain evaluation. Approved for full billed amount ($680.50) on 2026-03-29; payment disbursement in progress.' where claim_reference = 'CLM-2026-000002';
update claims set description = 'Routine diabetes management consultation. Denied: service not covered under current plan tier.' where claim_reference = 'CLM-2026-000003';
update claims set description = 'Physical therapy session for shoulder impingement. Under clinical review for medical necessity; no determination made yet.' where claim_reference = 'CLM-2026-000004';
update claims set description = 'Diagnostic workup for unspecified chest discomfort. Awaiting supporting documentation from provider before review.' where claim_reference = 'CLM-2026-000005';

update claims set description = 'Routine dental cleaning and exam. Approved and paid in full ($175.00) on 2026-03-06.' where claim_reference = 'CLM-2026-000006';
update claims set description = 'Treatment for periodontal disease. Approved for full billed amount ($420.00) on 2026-04-11.' where claim_reference = 'CLM-2026-000007';
update claims set description = 'Emergency room visit for ankle injury. Awaiting itemized billing statement from facility before review.' where claim_reference = 'CLM-2026-000008';
update claims set description = 'Dental filling replacement. Denied: duplicate claim submitted for same service date.' where claim_reference = 'CLM-2026-000009';
update claims set description = 'Follow-up for chronic joint pain. Under clinical review; additional medical records requested from provider.' where claim_reference = 'CLM-2026-000010';

update claims set description = 'Urgent care visit for asthma exacerbation. Approved and paid in full ($290.00) on 2026-02-16.' where claim_reference = 'CLM-2026-000011';
update claims set description = 'Evaluation for persistent cough. Awaiting adjuster assignment.' where claim_reference = 'CLM-2026-000012';
update claims set description = 'Emergency treatment for forearm fracture. Approved for full billed amount ($1200.00) on 2026-05-09.' where claim_reference = 'CLM-2026-000013';
update claims set description = 'Physical therapy for knee osteoarthritis. Under clinical review for treatment plan duration.' where claim_reference = 'CLM-2026-000014';
update claims set description = 'Urgent care visit for acute sinusitis. Denied: diagnosis code not covered for urgent care setting.' where claim_reference = 'CLM-2026-000015';