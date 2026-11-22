{{/*
Expand the name of the chart.
*/}}
{{- define "ragbox.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "ragbox.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "ragbox.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "ragbox.labels" -}}
helm.sh/chart: {{ include "ragbox.chart" . }}
{{ include "ragbox.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "ragbox.selectorLabels" -}}
app.kubernetes.io/name: {{ include "ragbox.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "ragbox.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "ragbox.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Create the name of the secret
*/}}
{{- define "ragbox.secretName" -}}
{{- if .Values.secrets.existingSecret }}
{{- .Values.secrets.existingSecret }}
{{- else }}
{{- include "ragbox.fullname" . }}-secrets
{{- end }}
{{- end }}

{{/*
Create the name of the configmap
*/}}
{{- define "ragbox.configmapName" -}}
{{- include "ragbox.fullname" . }}-config
{{- end }}

{{/*
PostgreSQL host
*/}}
{{- define "ragbox.postgresql.host" -}}
{{- if .Values.postgresql.enabled }}
{{- include "ragbox.postgresql.fullname" . }}
{{- else }}
{{- .Values.externalDatabase.host }}
{{- end }}
{{- end }}

{{/*
PostgreSQL port
*/}}
{{- define "ragbox.postgresql.port" -}}
{{- if .Values.postgresql.enabled }}
{{- 5432 }}
{{- else }}
{{- .Values.externalDatabase.port }}
{{- end }}
{{- end }}

{{/*
PostgreSQL database
*/}}
{{- define "ragbox.postgresql.database" -}}
{{- if .Values.postgresql.enabled }}
{{- .Values.postgresql.auth.database }}
{{- else }}
{{- .Values.externalDatabase.database }}
{{- end }}
{{- end }}

{{/*
Bundled PostgreSQL resource name (StatefulSet, Service and generated Secret)
*/}}
{{- define "ragbox.postgresql.fullname" -}}
{{- include "ragbox.fullname" . }}-postgresql
{{- end }}

{{/*
Bundled PostgreSQL selector labels
*/}}
{{- define "ragbox.postgresql.selectorLabels" -}}
{{ include "ragbox.selectorLabels" . }}
app.kubernetes.io/component: postgresql
{{- end }}

{{/*
PostgreSQL secret name - returns the appropriate secret for DB credentials
When using bundled PostgreSQL: the chart-generated <fullname>-postgresql secret (or auth.existingSecret)
When using external database: use ragbox secret or existingSecret
*/}}
{{- define "ragbox.postgresql.secretName" -}}
{{- if .Values.postgresql.enabled }}
{{- if .Values.postgresql.auth.existingSecret }}
{{- .Values.postgresql.auth.existingSecret }}
{{- else }}
{{- include "ragbox.postgresql.fullname" . }}
{{- end }}
{{- else }}
{{- /* External database - use ragbox secret or existingSecret */}}
{{- if .Values.externalDatabase.existingSecret }}
{{- .Values.externalDatabase.existingSecret }}
{{- else }}
{{- include "ragbox.secretName" . }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Resolve a secret value so that it is stable across upgrades:
  1. the explicitly configured value, if set
  2. otherwise the value already stored in the in-cluster Secret (helm upgrade)
  3. otherwise a new random 32-character string (first install)
Usage: include "ragbox.secretValue" (dict "ctx" $ "secretName" "<name>" "key" "<key>" "value" .Values.x)
Note: `helm template` and client-side dry runs cannot look up the cluster, so set
values explicitly when rendering manifests outside of `helm install/upgrade` (e.g. GitOps).
*/}}
{{- define "ragbox.secretValue" -}}
{{- if .value -}}
{{- .value -}}
{{- else -}}
{{- $existing := lookup "v1" "Secret" .ctx.Release.Namespace .secretName -}}
{{- if and $existing $existing.data (hasKey $existing.data .key) -}}
{{- index $existing.data .key | b64dec -}}
{{- else -}}
{{- randAlphaNum 32 -}}
{{- end -}}
{{- end -}}
{{- end }}

{{/*
PostgreSQL password key - returns the correct secret key for the password
The bundled PostgreSQL secret uses 'password', the ragbox secret uses 'pg-password'
*/}}
{{- define "ragbox.postgresql.passwordKey" -}}
{{- if .Values.postgresql.enabled -}}
password
{{- else -}}
{{- .Values.externalDatabase.passwordKey | default "pg-password" -}}
{{- end -}}
{{- end }}

{{/*
PostgreSQL user key - returns the correct secret key for the username
*/}}
{{- define "ragbox.postgresql.userKey" -}}
{{- if .Values.postgresql.enabled -}}
{{- else -}}
{{- .Values.externalDatabase.userKey | default "pg-user" -}}
{{- end -}}
{{- end }}

{{/*
PostgreSQL username - returns the database username
*/}}
{{- define "ragbox.postgresql.username" -}}
{{- if .Values.postgresql.enabled }}
{{- .Values.postgresql.auth.username }}
{{- else }}
{{- .Values.externalDatabase.username | default "rag" }}
{{- end }}
{{- end }}

{{/*
Database credential env vars (PG_USER, PG_PASSWORD), shared by the deployment and the
migration job
*/}}
{{- define "ragbox.databaseCredentialsEnv" -}}
{{- if .Values.postgresql.enabled }}
# Bundled PostgreSQL - username from values, password from the chart-generated secret
- name: PG_USER
  value: {{ include "ragbox.postgresql.username" . | quote }}
- name: PG_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "ragbox.postgresql.secretName" . }}
      key: {{ include "ragbox.postgresql.passwordKey" . }}
{{- else }}
# External database - credentials from secret
- name: PG_USER
  valueFrom:
    secretKeyRef:
      name: {{ include "ragbox.postgresql.secretName" . }}
      key: {{ include "ragbox.postgresql.userKey" . }}
- name: PG_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "ragbox.postgresql.secretName" . }}
      key: {{ include "ragbox.postgresql.passwordKey" . }}
{{- end }}
{{- end }}

{{/*
The Helm hooks the migration job runs as. Before an upgrade it can always run: the database
and its credentials exist from the previous release. Before the first install only when the
chart creates neither - an external database whose credentials are in an existing secret -
since hooks run before the release's own resources exist.
*/}}
{{- define "ragbox.migrations.hooks" -}}
{{- if and (not .Values.postgresql.enabled) .Values.externalDatabase.existingSecret -}}
pre-install,pre-upgrade
{{- else -}}
pre-upgrade
{{- end -}}
{{- end }}
