package com.highcapable.yukihookapi.hook.type.java

import org.json.JSONArray

@Suppress("UNCHECKED_CAST")
val ArrayListClass = ArrayList::class.java as Class<ArrayList<*>>
val BooleanType: Class<Boolean> = Boolean::class.javaPrimitiveType!!
val CharSequenceClass: Class<CharSequence> = CharSequence::class.java
val IntType: Class<Int> = Int::class.javaPrimitiveType!!
val JSONArrayClass: Class<JSONArray> = JSONArray::class.java
@Suppress("UNCHECKED_CAST")
val ListClass = List::class.java as Class<List<*>>
val LongType: Class<Long> = Long::class.javaPrimitiveType!!
@Suppress("UNCHECKED_CAST")
val MapClass = Map::class.java as Class<Map<*, *>>
val StringClass: Class<String> = String::class.java
val UnitType: Class<Void> = Void.TYPE
