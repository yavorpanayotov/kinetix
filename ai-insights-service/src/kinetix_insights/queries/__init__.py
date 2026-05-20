"""Built-in saved-query templates and their loader.

This package holds the five built-in saved-query JSON resources
(``limit-breaches.json`` etc.) shipped with the service, plus a small
loader (:mod:`kinetix_insights.queries.loader`) that resolves a template
by id for the ``POST /api/v1/insights/queries/{id}/run`` route. There is
no server-side persistence — user-saved queries live only in the
browser's ``localStorage`` (see the v2 plan, PR 8).
"""
