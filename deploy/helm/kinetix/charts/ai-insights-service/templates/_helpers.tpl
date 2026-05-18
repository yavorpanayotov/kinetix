{{- define "ai-insights-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "ai-insights-service.fullname" -}}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "ai-insights-service.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{ include "ai-insights-service.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: kinetix
{{- end }}

{{- define "ai-insights-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "ai-insights-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
