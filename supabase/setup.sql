-- Padel Grabber · configuración de Supabase
-- Pégalo entero en Supabase > SQL Editor > New query y pulsa "Run".

-- 1) Tablas
create table if not exists public.partidos (
  id          text primary key,
  creado      timestamptz not null default now(),
  fecha       timestamptz,
  club        text,
  pista       text,
  equipo_a    text,
  equipo_b    text,
  resultado   text,
  ganador     int,
  duracion_s  int,
  puntos      int
);

create table if not exists public.videos (
  id          bigint generated always as identity primary key,
  creado      timestamptz not null default now(),
  partido_id  text not null,
  tipo        text not null,          -- resumen | repeticiones | completo | angulo:<nombre>
  nombre      text not null,          -- ruta dentro del bucket
  url         text not null,
  bytes       bigint,
  unique (partido_id, nombre)
);

create index if not exists videos_partido_idx on public.videos (partido_id);

-- 2) Permisos: la app y la web usan la clave pública (anon)
grant usage on schema public to anon, authenticated;
grant select, insert, update on public.partidos to anon, authenticated;
grant select, insert, update on public.videos   to anon, authenticated;
grant usage, select on all sequences in schema public to anon, authenticated;

alter table public.partidos enable row level security;
alter table public.videos   enable row level security;

drop policy if exists "partidos lectura" on public.partidos;
drop policy if exists "partidos alta"    on public.partidos;
drop policy if exists "partidos cambio"  on public.partidos;
create policy "partidos lectura" on public.partidos for select to anon, authenticated using (true);
create policy "partidos alta"    on public.partidos for insert to anon, authenticated with check (true);
create policy "partidos cambio"  on public.partidos for update to anon, authenticated using (true) with check (true);

drop policy if exists "videos lectura" on public.videos;
drop policy if exists "videos alta"    on public.videos;
drop policy if exists "videos cambio"  on public.videos;
create policy "videos lectura" on public.videos for select to anon, authenticated using (true);
create policy "videos alta"    on public.videos for insert to anon, authenticated with check (true);
create policy "videos cambio"  on public.videos for update to anon, authenticated using (true) with check (true);

-- 3) Almacén de vídeos (público para poder descargarlos)
insert into storage.buckets (id, name, public)
values ('partidos', 'partidos', true)
on conflict (id) do update set public = true;

drop policy if exists "pg subir"      on storage.objects;
drop policy if exists "pg actualizar" on storage.objects;
drop policy if exists "pg leer"       on storage.objects;
create policy "pg subir"      on storage.objects for insert to anon, authenticated with check (bucket_id = 'partidos');
create policy "pg actualizar" on storage.objects for update to anon, authenticated using (bucket_id = 'partidos') with check (bucket_id = 'partidos');
create policy "pg leer"       on storage.objects for select to anon, authenticated using (bucket_id = 'partidos');
