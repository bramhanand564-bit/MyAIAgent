export const ROUTE_OUTPUT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['thought', 'action', 'skill_id', 'parameters', 'reply_text'],
  properties: {
    thought: { type: 'string', maxLength: 180 },
    action: { type: 'string', enum: ['call_skill', 'web_search', 'read_document', 'direct_reply'] },
    skill_id: { type: ['string', 'null'] },
    parameters: { type: 'object' },
    reply_text: { type: 'string', maxLength: 500 },
  },
} as const;

export const ROUTE_GBNF = String.raw`
root ::= "{" ws "\"thought\"" ws ":" ws string ws "," ws "\"action\"" ws ":" ws action ws "," ws "\"skill_id\"" ws ":" ws (string | "null") ws "," ws "\"parameters\"" ws ":" ws object ws "," ws "\"reply_text\"" ws ":" ws string ws "}" ws
action ::= "\"call_skill\"" | "\"web_search\"" | "\"read_document\"" | "\"direct_reply\""
object ::= "{" ws (string ws ":" ws value (ws "," ws string ws ":" ws value)*)? ws "}"
value ::= string | number | "true" | "false" | "null" | object | array
array ::= "[" ws (value (ws "," ws value)*)? ws "]"
string ::= "\"" ([^"\\x00-\\x1F\\x7F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4}))* "\""
number ::= "-"? ([0-9] | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
ws ::= [ \t\n\r]*
`;

export function safeParseAgentJson(input: string): unknown | null {
  const start = input.indexOf('{');
  const end = input.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  try {
    return JSON.parse(input.slice(start, end + 1)) as unknown;
  } catch {
    return null;
  }
}
