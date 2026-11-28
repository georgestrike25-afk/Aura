# Aura Signature: pone en tus archivos los títulos nuevos de la galería.
# Busca en D:\Coleccion Aura y en D:\Coleccion Aura - Descartadas. Solo cambia el nombre del archivo.
$carpetas = 'D:\Coleccion Aura', 'D:\Coleccion Aura - Descartadas'
$cambios = @(
  @('El jardín plegado (2)', 'El capullo que guarda un rubí'),
  @('La gruta de nácar (2)', 'La perla en el velo de nácar'),
  @('Playa y atardecer', 'El ciervo del espejo rosado'),
  @('El martín pescador del remanso (2)', 'El martín pescador del junco'),
  @('Campo y cielo 2', 'El astronauta de las flores secas'),
  @('Espacio', 'El que sostiene la tormenta'),
  @('Flores y cielo', 'La lavanda de la luna nueva'),
  @('Bosque 2', 'El ciervo blanco del arroyo'),
  @('Cielo 5', 'El zorro del último sol'),
  @('Selva', 'El ciervo de los rayos verdes'),
  @('Avión y torre', 'El farol de la bruma'),
  @('Patrón y edificios', 'La puerta del lago'),
  @('Templo y lago', 'La escalera hacia la luz'),
  @('Cielo', 'El paraguas de la lluvia de luz'),
  @('Desierto y playa', 'El que carga la luna'),
  @('Espacio 2', 'El koi de seda'),
  @('Retrato 10', 'La dríade que despierta'),
  @('Estatua y edificios', 'El chelista de la niebla'),
  @('Montaña y cielo 2', 'La bufanda sobre las nubes'),
  @('Retrato 2', 'La mujer del trigal'),
  @('Cielo 4', 'El ciervo de los cerezos'),
  @('Cielo y desierto', 'La ballena del desierto'),
  @('Estatua y edificios 2', 'El busto de oro'),
  @('Retrato 3', 'La que suelta los pájaros'),
  @('Retrato 6', 'El poeta de las grullas'),
  @('Retrato 7', 'La flor sobre el rojo'),
  @('Retrato', 'La del sol carmesí'),
  @('Lago y plantas 2', 'El árbol de las luciérnagas'),
  @('Lago y plantas', 'El arce de la isla'),
  @('Cielo 3', 'La ballena de la aurora'),
  @('Torre y rocas', 'El faro de libros'),
  @('Vehículo y playa', 'El bote del atardecer violeta')
)
function Norm($t) { return $t.Normalize([Text.NormalizationForm]::FormC).Trim().ToLowerInvariant() }
$ext = '.jpg','.jpeg','.png','.webp'
$todos = foreach ($c in $carpetas) { if (Test-Path -LiteralPath $c) { Get-ChildItem -LiteralPath $c -Recurse -File | Where-Object { $ext -contains $_.Extension.ToLower() } } }
$plan = @(); $dudas = @(); $faltan = @()
foreach ($p in $cambios) {
  $viejo = Norm $p[0]
  $hay = @($todos | Where-Object { (Norm $_.BaseName) -eq $viejo })
  if ($hay.Count -eq 1) { $plan += ,@($hay[0], $p[1]) }
  elseif ($hay.Count -gt 1) { $dudas += ("{0}  ({1} archivos con ese nombre, no se toca)" -f $p[0], $hay.Count) }
  else { $faltan += $p[0] }
}
foreach ($x in $plan) { Write-Host ("{0}  ->  {1}{2}" -f $x[0].FullName, $x[1], $x[0].Extension) }
if ($dudas) { Write-Host ''; Write-Host 'Con nombre repetido (no se cambian):'; $dudas | ForEach-Object { Write-Host "  $_" } }
if ($faltan) { Write-Host ''; Write-Host ('No encontrados ({0}): {1}' -f $faltan.Count, ($faltan -join ', ')) }
if (-not $plan) { Write-Host 'No hay nada para renombrar.'; Read-Host 'Enter para salir'; exit }
Write-Host ''
if ((Read-Host ("Se van a renombrar {0} archivos. Escribí S y Enter para seguir" -f $plan.Count)) -notin 's','S') { Write-Host 'Cancelado.'; exit }
foreach ($x in $plan) {
  $f = $x[0]; $nuevo = $x[1] + $f.Extension
  if (Test-Path -LiteralPath (Join-Path $f.DirectoryName $nuevo)) { Write-Host "Ya existe, se saltea: $nuevo"; continue }
  Rename-Item -LiteralPath $f.FullName -NewName $nuevo
}
Write-Host 'Listo.'
Read-Host 'Enter para salir'
