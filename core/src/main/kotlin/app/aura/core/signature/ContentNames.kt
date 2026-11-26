package app.aura.core.signature

/**
 * Agrupa y nombra las fotos de «Mis obras» según lo que reconoce el etiquetado de imágenes del teléfono
 * (ML Kit, en inglés). Todo es local: solo traduce etiquetas a un grupo y a un nombre en español.
 */
object ContentNames {
    enum class Group(val title: String) {
        MOUNTAINS("Montañas"),
        WATER("Mar y agua"),
        SKY("Cielo y noche"),
        NATURE("Naturaleza"),
        CITY("Ciudad"),
        ANIMALS("Animales"),
        PEOPLE("Personas"),
        VEHICLES("Vehículos"),
        ART("Arte"),
        OTHER("Otras"),
    }

    /** Etiqueta de ML Kit → (grupo, nombre en español). */
    private val words: Map<String, Pair<Group, String>> = buildMap {
        fun add(g: Group, vararg pairs: Pair<String, String>) = pairs.forEach { (en, es) -> put(en.lowercase(), g to es) }
        add(Group.MOUNTAINS, "Mountain" to "Montaña", "Hill" to "Colinas", "Glacier" to "Glaciar", "Cliff" to "Acantilado",
            "Rock" to "Rocas", "Volcano" to "Volcán", "Canyon" to "Cañón", "Desert" to "Desierto", "Snow" to "Nieve")
        add(Group.WATER, "Sea" to "Mar", "Beach" to "Playa", "Lake" to "Lago", "River" to "Río", "Waterfall" to "Cascada",
            "Surfboard" to "Surf", "Pier" to "Muelle", "Underwater" to "Bajo el agua", "Wave" to "Olas", "Pool" to "Pileta")
        add(Group.SKY, "Sky" to "Cielo", "Cloud" to "Nubes", "Sunset" to "Atardecer", "Moon" to "Luna", "Star" to "Estrellas",
            "Fireworks" to "Fuegos artificiales", "Space" to "Espacio", "Aurora" to "Aurora", "Rainbow" to "Arcoíris", "Night" to "Noche")
        add(Group.NATURE, "Flower" to "Flores", "Plant" to "Plantas", "Tree" to "Árboles", "Forest" to "Bosque", "Leaf" to "Hojas",
            "Garden" to "Jardín", "Grass" to "Pasto", "Jungle" to "Selva", "Field" to "Campo", "Petal" to "Pétalos", "Mushroom" to "Hongos")
        add(Group.CITY, "Skyscraper" to "Rascacielos", "Building" to "Edificios", "City" to "Ciudad", "Bridge" to "Puente",
            "Tower" to "Torre", "Road" to "Ruta", "Street" to "Calle", "Neon" to "Neón", "Church" to "Iglesia", "Castle" to "Castillo",
            "Ruins" to "Ruinas", "Stadium" to "Estadio")
        add(Group.ANIMALS, "Dog" to "Perro", "Cat" to "Gato", "Bird" to "Pájaro", "Horse" to "Caballo", "Fish" to "Peces",
            "Butterfly" to "Mariposa", "Insect" to "Insecto", "Bear" to "Oso", "Pet" to "Mascota", "Duck" to "Pato", "Penguin" to "Pingüino",
            "Lion" to "León", "Tiger" to "Tigre", "Elephant" to "Elefante", "Rabbit" to "Conejo", "Owl" to "Búho", "Whale" to "Ballena",
            "Wolf" to "Lobo", "Fox" to "Zorro", "Animal" to "Animal", "Cattle" to "Vacas", "Dinosaur" to "Dinosaurio")
        add(Group.PEOPLE, "Selfie" to "Retrato", "Smile" to "Sonrisa", "Person" to "Persona", "Beard" to "Retrato", "Hair" to "Retrato",
            "Eyelash" to "Retrato", "Baby" to "Bebé", "Dude" to "Retrato", "Muscle" to "Atleta", "Dancing" to "Baile", "Bride" to "Boda")
        add(Group.VEHICLES, "Car" to "Auto", "Airplane" to "Avión", "Boat" to "Barco", "Bicycle" to "Bicicleta", "Motorcycle" to "Moto",
            "Train" to "Tren", "Vehicle" to "Vehículo", "Helicopter" to "Helicóptero", "Rocket" to "Cohete", "Bus" to "Colectivo")
        add(Group.ART, "Painting" to "Pintura", "Sculpture" to "Escultura", "Art" to "Arte", "Cartoon" to "Dibujo", "Pattern" to "Patrón",
            "Statue" to "Estatua", "Mural" to "Mural", "Comics" to "Historieta", "Illustration" to "Ilustración")
    }

    data class Result(val group: Group, val name: String)

    /**
     * [labels]: (etiqueta en inglés, confianza 0–1). El grupo es el que más confianza suma; el nombre es
     * la etiqueta más fuerte, más una segunda de otro tema si la hay («Montaña y cielo»).
     */
    fun classify(labels: List<Pair<String, Float>>): Result {
        val known = labels.filter { it.second >= 0.5f }
            .mapNotNull { (en, c) -> words[en.lowercase()]?.let { Triple(it.first, it.second, c) } }
            .sortedByDescending { it.third }
        if (known.isEmpty()) return Result(Group.OTHER, "Mi foto")
        val group = known.groupBy { it.first }.maxBy { (_, v) -> v.sumOf { it.third.toDouble() } }.key
        val main = known.first { it.first == group }.second
        val second = known.firstOrNull { it.first != group && it.second != main }?.second
        return Result(group, if (second != null) "$main y ${second.lowercase()}" else main)
    }

    /** Evita nombres repetidos: «Montaña», «Montaña 2», «Montaña 3»… */
    fun unique(name: String, taken: Collection<String>): String {
        if (name !in taken) return name
        var n = 2
        while ("$name $n" in taken) n++
        return "$name $n"
    }

    /**
     * Huella visual (dHash de 64 bits) a partir de una imagen en grises de 9×8 [gray] (0–255, fila por fila).
     * Dos fotos iguales (aunque estén recomprimidas o con otro tamaño) quedan a pocos bits de distancia.
     */
    fun dHash(gray: IntArray): Long {
        require(gray.size == 72)
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            h = h shl 1
            if (gray[y * 9 + x] > gray[y * 9 + x + 1]) h = h or 1L
        }
        return h
    }

    fun sameImage(a: Long, b: Long): Boolean = java.lang.Long.bitCount(a xor b) <= 6

    /**
     * Huella completa: dHash (forma) + color medio de una grilla de 4×4 (ARGB, fila por fila). Así dos
     * fotos con la misma composición pero otros colores no se confunden. Texto: 16 hex + 96 hex.
     */
    fun fingerprint(gray: IntArray, colors4x4: IntArray): String {
        require(colors4x4.size == 16)
        return "%016x".format(dHash(gray)) + colors4x4.joinToString("") { "%06x".format(it and 0xFFFFFF) }
    }

    /** ¿Es la misma foto? Con huellas viejas (solo dHash) compara solo la forma. */
    fun same(a: String, b: String): Boolean {
        val ha = a.take(16).toULongOrNull(16)?.toLong() ?: return false
        val hb = b.take(16).toULongOrNull(16)?.toLong() ?: return false
        if (!sameImage(ha, hb)) return false
        if (a.length < 112 || b.length < 112) return true
        var diff = 0
        for (i in 0 until 48) {
            val x = a.substring(16 + i * 2, 18 + i * 2).toIntOrNull(16) ?: return false
            val y = b.substring(16 + i * 2, 18 + i * 2).toIntOrNull(16) ?: return false
            diff += kotlin.math.abs(x - y)
        }
        return diff / 48 <= 12
    }
}
