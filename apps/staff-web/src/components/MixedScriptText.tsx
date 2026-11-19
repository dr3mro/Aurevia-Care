/** Props for a bidirectional clinical label. */
export interface MixedScriptTextProps {
  text: string;
  lang: string;
}

/** Isolate a mixed-script run so surrounding direction cannot reorder it. */
export function MixedScriptText({ text, lang }: MixedScriptTextProps) {
  return (
    <bdi lang={lang} dir="auto">
      {text}
    </bdi>
  );
}
