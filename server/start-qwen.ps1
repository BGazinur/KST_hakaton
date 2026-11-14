param([string]$Device = 'Vulkan1')
$ErrorActionPreference = 'Stop'
$modelDirectory = Join-Path $PSScriptRoot 'data/qwen'
$runtime = Join-Path $modelDirectory 'runtime/llama-server.exe'
$weights = Join-Path $modelDirectory 'Qwen3VL-2B-Instruct-Q4_K_M.gguf'
$projector = Join-Path $modelDirectory 'mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf'
foreach ($path in @($runtime, $weights, $projector)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing local Qwen file: $path" }
}
if ((Get-Item -LiteralPath $weights).Length -ne 1107409952 -or (Get-Item -LiteralPath $projector).Length -ne 445053216) {
    throw 'The local Qwen model download is incomplete.'
}
& $runtime --model $weights --mmproj $projector --alias inspection-qwen --host 127.0.0.1 --port 8081 `
    --device $Device --n-gpu-layers 99 --ctx-size 4096 --parallel 1
exit $LASTEXITCODE
