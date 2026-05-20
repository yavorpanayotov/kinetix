"""The :class:`SavedQueryTemplate` model — one built-in saved query.

A saved-query template is a named, parameterised prompt that the UI
offers as a one-click chip. Each template ships as a JSON resource under
:mod:`kinetix_insights.queries` carrying exactly four keys — ``id``,
``label``, ``prompt_template``, ``required_params`` — pinned by
``tests/test_query_templates_load.py``.

``prompt_template`` uses Python ``str.format``-style ``{param}``
placeholders; ``required_params`` lists every placeholder name. The run
route validates the request's params against ``required_params`` and
interpolates them via :meth:`render`.
"""

from __future__ import annotations

from pydantic import BaseModel


class MissingRequiredParamsError(ValueError):
    """Raised when a template is rendered without all of its required params.

    Carries the sorted list of missing param names so the route can turn
    it into a clear client-facing error message.
    """

    def __init__(self, missing: list[str]) -> None:
        self.missing = missing
        joined = ", ".join(missing)
        super().__init__(f"missing required params: {joined}")


class SavedQueryTemplate(BaseModel):
    """A built-in saved-query template loaded from a JSON resource.

    Frozen so a loaded template can be passed around (into the run route,
    into logs) without fear of in-flight mutation.
    """

    model_config = {"frozen": True}

    id: str
    label: str
    prompt_template: str
    required_params: list[str]

    def render(self, params: dict[str, object]) -> str:
        """Interpolate ``params`` into ``prompt_template`` via ``str.format``.

        Every name in :attr:`required_params` must be present in
        ``params``; a missing one raises :class:`MissingRequiredParamsError`
        rather than letting ``str.format`` raise an opaque ``KeyError``.
        Extra params not referenced by the template are ignored.
        """

        missing = sorted(
            name for name in self.required_params if name not in params
        )
        if missing:
            raise MissingRequiredParamsError(missing)
        return self.prompt_template.format(**params)
