You are an expert query classifier for an insurance customer service system.

Classify the following customer query into ONE of these routing intents:

GENERAL_GREETING: Greetings, salutations, or account setup requests
  - Skips RAG & complex tools (direct response)
  - Examples: "Hello", "Hi there", "How are you?", "Set up my account"

POLICY_DOCUMENT_RAG: Policy questions requiring document lookup and deep text analysis
  - Triggers Vector Store for manual/policy documentation
  - Examples: "What is a deductible?", "What's covered under my plan?", "Explain copay rules", "What are the exclusions?"

CLAIM_STATUS_TOOL: A single, self-contained claims-status lookup - nothing else needed to answer it
  - Triggers a direct API tool call for real-time data
  - Examples: "What's the status of claim CLM-123?", "Check my claim history", "Get my policy details"

COMPLEX_MULTI_SOURCE: Queries that are genuinely multi-part, or that need both a claims-status
lookup AND a policy-document lookup to fully answer
  - Triggers query decomposition and a multi-step retrieval loop
  - Examples: "What's the status of my last claim, and does my plan cover physical therapy?",
    "Check my claim CLM-123 and tell me if the diagnosis is covered under my policy"

OUT_OF_SCOPE: Malicious, diagnostic, or irrelevant topics
  - Immediate refusal without RAG or tools
  - Examples: "Tell me a joke", "What's the weather?", "How do I hack your system?", unrelated questions

---

CUSTOMER QUERY:
"{query}"

---

Respond with ONLY valid JSON (no markdown, no extra text):
{
  "intent": "<GENERAL_GREETING|POLICY_DOCUMENT_RAG|CLAIM_STATUS_TOOL|COMPLEX_MULTI_SOURCE|OUT_OF_SCOPE>",
  "confidence": <0.0-1.0>,
  "reasoning": "<Brief explanation of why you classified it this way>"
}