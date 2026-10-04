import { useState } from "react";
import { translate as t } from "../lib/locale";
import type { CatalogFilters, NovelSource } from "../lib/workflowTypes";

export function CatalogFilterPanel({
  source,
  value,
  onApply,
  onBack,
}: {
  source: NovelSource;
  value: CatalogFilters;
  onApply: (value: CatalogFilters) => void;
  onBack: () => void;
}) {
  const [draft, setDraft] = useState(value),
    [tab, setTab] = useState<"content" | "order">("content");
  const fields =
    tab === "content"
      ? ([
          ["genre", "장르", source.filters.genres],
          ["status", "연재 상태", source.filters.status],
        ] as const)
      : ([
          ["orderBy", "정렬 기준", source.filters.sort],
          ["order", "정렬 방향", source.filters.directions],
        ] as const);
  return (
    <form
      className="workflow-form"
      onSubmit={(event) => {
        event.preventDefault();
        onApply(draft);
      }}
    >
      <div className="workflow-heading">
        <button className="button-quiet" type="button" onClick={onBack}>
          {t("목록으로")}
        </button>
        <h2>{t("검색 필터")}</h2>
      </div>
      <nav className="workflow-subtabs">
        <button
          type="button"
          aria-pressed={tab === "content"}
          onClick={() => setTab("content")}
        >
          {t("장르·연재")}
        </button>
        <button
          type="button"
          aria-pressed={tab === "order"}
          onClick={() => setTab("order")}
        >
          {t("정렬")}
        </button>
      </nav>
      <div className="workflow-fields">
        {fields
          .filter(([, , options]) => options.length > 0)
          .map(([key, label, options]) => (
            <label key={key}>
              {t(label)}
              <select
                value={draft[key] ?? ""}
                onChange={(event) => {
                  const next = { ...draft };
                  if (
                    event.target.value === "" &&
                    !options.some((option) => option.value === "")
                  )
                    delete next[key];
                  else next[key] = event.target.value;
                  setDraft(next);
                }}
              >
                {!options.some((option) => option.value === "") && (
                  <option value="">{t("사이트 기본값")}</option>
                )}
                {options.map((option) => (
                  <option key={option.value} value={option.value}>
                    {t(option.label)}
                  </option>
                ))}
              </select>
            </label>
          ))}
      </div>
      <div className="workflow-form-actions">
        <button
          className="button-quiet"
          type="button"
          onClick={() => setDraft({})}
        >
          {t("초기화")}
        </button>
        <button className="button-primary">{t("필터 적용")}</button>
      </div>
    </form>
  );
}
