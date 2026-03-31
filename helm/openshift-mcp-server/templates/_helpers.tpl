{{- define "openshift-mcp-server.fullname" -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "openshift-mcp-server.labels" -}}
app: {{ include "openshift-mcp-server.fullname" . }}
app.kubernetes.io/name: {{ include "openshift-mcp-server.fullname" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "openshift-mcp-server.selectorLabels" -}}
app: {{ include "openshift-mcp-server.fullname" . }}
{{- end }}
