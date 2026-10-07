# Release-name parser: known gaps

`catalog/ReleaseParser` turns a file name (plus its folders) into a title, year, season and episode numbers. This
file tracks what it still gets wrong and how it could be improved.

## Where it stands

Measured against 1,070 labelled names taken from the guessit and anitomy test suites, counting a name as correct
only when kind, title, year, season and episodes all match:

| Source            | Names | Fully correct |
|-------------------|------:|--------------:|
| anitomy (anime)   |   236 |         87.3% |
| guessit episodes  |   453 |         92.1% |
| guessit movies    |   224 |         89.3% |
| guessit various   |   157 |         87.3% |
| **Total**         | 1,070 |     **90.0%** |

By field: kind 97.6%, title 90.8%, year 99.6%, season 98.6%, episodes 97.2%.

On a real Real-Debrid library of about 1,000 files there are no known misparses that would put a file in the wrong
place.

The corpus is not in the repo (guessit's test data is LGPL), and neither is the evaluation harness. Rebuilding
both is the first item under [Improvements](#improvements).

## Real gaps

These are names a person would read correctly and the parser doesn't. They're ordered roughly by how often they
turn up in real libraries.

### Specials, OVAs, openings and endings
`Show - SP01`, `Show - EX01`, `Show OVA - 01`, `Show ED2`, `Show NCOP1` are dropped to movies or keep the marker in
the title. They should be episodes in season 0, with the marker kept as a kind so that openings and endings can be
hidden.

### Split seasons ("cour" and "part")
`Show 2nd Cour - 01`, `Show cour 2`, `Show S3 P2 - 01`, `Show.S04.P2`: the cour or part stays in the title, and
`cour 2` on its own is read as episode 2. These should be stripped from the title, and could be recorded as a part
for metadata matching, since TMDB and AniList split them differently.

### Season and episode run together without corroboration
`Show.Name.101.x264-GRP` and `Show.Name.2005.211.Title` are S01E01 and S02E11, but the parser only splits a 3–4
digit number when there's a TV tag or folder, because `Movie.300.x264` is a film. Metadata can settle this: if
the title matches a show, split the number; if it matches a film, don't.

### Bare trailing numbers
`Show Name 10 720p`, `Show Name 13-16`, `BLUE DROP 10 (1)`: a number after the title with nothing else to say it's
an episode. They're kept as movies on purpose ("District 9", "Ocean's 11"). `Catalog` already turns them into
episodes when a sibling file names the same show, and metadata would catch the rest.

### Episode number followed by a title, without a dash
`RWBY 14 Forever Fall Part 2`, `Chapter 19 The Convert`, `Show Name 445 VOSTFR par …`: there's no dash after the
number and it isn't zero-padded, so it's read as part of the title.

### Scene group prefix with no release folder
`grp-the.movie.2016.1080p.mkv` and `FoV-Show.Name.S01E01` keep the `grp-` prefix. It's only removed when a
release folder around the file names the title. A lower-case prefix of 2–6 letters followed by a hyphen at the
start of an all-lower-case scene name could be dropped, but `spider-man.2002` shows the risk.

### Date-based names
`09.03.08.Movie.(1991)` reads the date as an episode, and `Show.2010.11.23` dated episodes aren't supported. Air
dates (`YYYY.MM.DD`, `DD.MM.YY`) should be recognised and, for shows, matched to episodes by air date through
metadata.

### Extras numbered inside a film's name
`Bond-f21-Casino_Royale-x02-Stunts`, `Movie-(1999)-x02-Interview-1996`: the `xNN` marks bonus material. These could
be treated as extras, like the `Extras/` folder already is.

### Episode list with a bare second number
`S03E21.22` is episodes 21 and 22, but a bare `22` after an episode is too easily a resolution or part of a title to
take without more evidence. `493-498 & 500-507` (two ranges) isn't supported either.

### Languages the markers don't cover
Turkish `2.Sezon 7.Bolum`, and in general markers where the number comes before the word. Add them as libraries
need them.

### Alternative titles and stray tags in film names
`Dragon Ball Z - Resurrection 'F' aka Frieza (2015)` keeps the "aka" alternative, and `The.Social.Network.KP.HDR.2160p`
keeps the unknown `KP` tag, so neither matches. Splitting on ` aka ` and treating short all-caps tokens right before
the release tags as tags would fix both.

### Whole name in brackets
`[ Show S02E10 1080p WEB-DL ]` is read as a bracketed group and not parsed.

### Season-1 vs. absolute numbering in one show
The same anime can come as `Show - 01` (absolute) in one release and `Show S01E01` in another, which shows up as
two numbering schemes in one show. Metadata episode counts can map one onto the other.

### Mixed release numbers
`Show - 29 (04)` (absolute and in-season numbers together) takes the first, `Nichijou_05_1080.BD` takes `1080` as
the episode, and `[Group] 0 [640x360]` (a film titled "0") is read as episode 0.

## Labelling conventions, not bugs

Most of the remaining corpus failures are cases where the labels make a choice the app shouldn't copy, because
metadata search does better with the fuller title:

- **Subtitles and alternative titles after ` - `:** `Garo - Vanishing Line`, `Show - Other Name - 02`,
  `Queen - A Kind of Magic`. guessit keeps only the part before the dash, which would merge different series.
- **Sequel and part numbers:** `The Godfather Part III` is labelled `The Godfather`.
- **Episode titles before the show:** `The Power of Suggestion - Mind Field S2 (Ep 6)` is labelled with the episode
  title as the show.
- **Compact numbers on a known long-running show:** `One Piece - 720` is labelled S07E20. It's episode 720.
- **Bracketed words inside titles:** anitomy keeps `[Locodol]` and `(DSA)` in titles. Brackets are dropped here
  because they're almost always tags.
- **Artist or studio prefixes:** `Justin Timberlake - MTV VMAs 2013`, studio names before scene titles.
- **Labels for names that can't exist on disk:** names containing `/` are split into folders by the test harness.

## Improvements

1. **Commit an evaluation harness.** Put a small corpus of our own (invented and real-library names, no guessit
   data) in `src/test/resources` with a test that reports accuracy by field and fails on regressions. Also keep a
   script that runs the full guessit and anitomy corpus when it's downloaded locally.
2. **Use metadata to break ties.** Have the parser return its alternatives when it isn't sure (movie or episode;
   `101` as S01E01 or episode 101; with or without the subtitle) and let TMDB/AniList matching pick the one that
   matches. This would fix most of the "bare number" and "subtitle" gaps above without making the parser guess.
3. **Make files in the same folder agree.** If most files in a folder parse as `Show - NN`, an odd one out
   (`Show NN v2`, a missing dash) should follow the majority. `Catalog.joinNumberedFilesToShows` does this for one
   case; it could become a general pass over each folder.
4. **Specials as season 0.** Recognise `SP`, `OVA`, `OAD`, `ONA`, `EX`, `NCOP`/`NCED`, `OP`/`ED`, and `Special`
   markers, and add a kind to `ParsedRelease` so the UI can hide openings and endings.
5. **Air-date episodes.** Add an air date to `ParsedRelease` for daily shows, matched to episodes through metadata.
6. **Consider tokenising.** The parser is a sequence of regexes over the whole name, which makes changes interact
   in surprising ways (most regressions while tuning came from one rule catching another's case). Splitting the
   name into tokens first and classifying each one (tag, year, marker, title word), as anitomy does, would make
   rules local. It's only worth doing if the gap list above grows rather than shrinks.
