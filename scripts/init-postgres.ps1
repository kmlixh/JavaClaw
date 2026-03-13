$ErrorActionPreference = 'Stop'

$hostName = $env:DB_HOST
if ([string]::IsNullOrWhiteSpace($hostName)) { $hostName = '10.173.108.120' }

$port = $env:DB_PORT
if ([string]::IsNullOrWhiteSpace($port)) { $port = '5433' }

$dbName = $env:DB_NAME
if ([string]::IsNullOrWhiteSpace($dbName)) { $dbName = 'java_claw' }

$dbUser = $env:DB_USER
if ([string]::IsNullOrWhiteSpace($dbUser)) { $dbUser = 'postgres' }

if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    throw 'DB_PASSWORD is required'
}

$env:PGPASSWORD = $env:DB_PASSWORD

Write-Host "Testing PostgreSQL connection to $hostName:$port/$dbName ..."
psql -h $hostName -p $port -U $dbUser -d $dbName -c "SELECT current_database(), current_user;"

Write-Host 'Applying Flyway migrations through Spring Boot ...'
$env:SPRING_PROFILES_ACTIVE = 'postgres'
.\gradlew.bat :agent-app:bootRun
