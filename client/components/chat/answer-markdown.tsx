import ReactMarkdown from "react-markdown";

/**
 * Renders an answer as markdown, with raw HTML off.
 *
 * `skipHtml` and no `rehype-raw`, matching how the biomarker pages render. It matters more
 * here: a biomarker page is content this project wrote and reviewed, while an answer is
 * generated text shaped partly by what someone typed into the question box. Tokens live in
 * this browser's memory, so an XSS bug would hand them over — and the contract lets the
 * answer contain anything the model produced.
 *
 * Only the inline and list elements the panel's design accounts for are allowed through.
 * Anything else renders as its own text rather than as structure, which is the safe failure.
 *
 * Safe to call on a partial answer: markdown that is still arriving is just markdown with an
 * unclosed construct, and react-markdown renders it as plain text until the rest shows up.
 */
export function AnswerMarkdown({ text }: { text: string }) {
  return (
    <ReactMarkdown
      skipHtml
      allowedElements={["p", "ul", "ol", "li", "strong", "em"]}
      unwrapDisallowed
    >
      {text}
    </ReactMarkdown>
  );
}
