@echo off
REM ---------------------------------------------------------------------------
REM  online-exam - one click start (Windows)
REM  起依赖 -> 等 MySQL -> 建表灌数据 -> 起服务。
REM  ASCII-only on purpose: .cmd is parsed with the OEM code page, and non-ASCII
REM  text in a batch file is the classic source of garbled output.
REM ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0.."

REM JWT secret: the app refuses to start without EXAM_JWT_SECRET (fail-closed),
REM so a fresh clone would stop here. Generate a random one per run --
REM random, never the old published default, never written to a file.
if not defined EXAM_JWT_SECRET (
  for /f "delims=" %%i in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')"') do set EXAM_JWT_SECRET=%%i
)
if not defined EXAM_JWT_SECRET (
  echo [fail] could not generate EXAM_JWT_SECRET.
  pause
  exit /b 1
)

echo [1/4] docker compose up -d
docker compose up -d
if errorlevel 1 goto :fail

echo [2/4] waiting for MySQL
for /l %%i in (1,1,40) do (
  docker exec exam-mysql mysqladmin ping -uroot -proot --silent >nul 2>&1 && goto :mysql_ok
  timeout /t 2 /nobreak >nul
)
echo [fail] MySQL did not become ready.
goto :fail
:mysql_ok

echo [3/4] schema + seed
docker exec -i exam-mysql mysql -uroot -proot --default-character-set=utf8mb4 < src\main\resources\db\schema.sql
docker exec -i exam-mysql mysql -uroot -proot --default-character-set=utf8mb4 < src\main\resources\db\seed.sql

echo [4/4] http://127.0.0.1:8081  -  press Ctrl+C to stop
mvn spring-boot:run
exit /b %errorlevel%

:fail
echo [fail] startup did not complete, see the messages above.
pause
exit /b 1
