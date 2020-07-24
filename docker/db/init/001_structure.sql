--
-- PostgreSQL database dump
--

-- Dumped from database version 9.6.15
-- Dumped by pg_dump version 11.5

-- Started on 2019-09-05 09:49:16

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;




--
-- TOC entry 3 (class 3079 OID 523295)
-- Name: postgis; Type: EXTENSION; Schema: -; Owner: 
--

CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;


--
-- TOC entry 4533 (class 0 OID 0)
-- Dependencies: 3
-- Name: EXTENSION postgis; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION postgis IS 'PostGIS geometry, geography, and raster spatial types and functions';


SET default_tablespace = '';

SET default_with_oids = false;



--
-- TOC entry 203 (class 1259 OID 524796)
-- Name: pgps; Type: TABLE; Schema: public; Owner: gps_buffer_user
--

CREATE TABLE public.pgps (
    id_pgps bigint NOT NULL,
    altitude double precision,
    altitude_error double precision,
    climb double precision,
    climb_error double precision,
    coordinate public.geometry,
    latitude_error double precision,
    longitude_error double precision,
    speed double precision,
    speed_error double precision,
    "time" timestamp with time zone,
    track double precision,
    track_error double precision
);


ALTER TABLE public.pgps OWNER TO gps_buffer_user;

--
-- TOC entry 202 (class 1259 OID 524794)
-- Name: pgps_id_pgps_seq; Type: SEQUENCE; Schema: public; Owner: gps_buffer_user
--

CREATE SEQUENCE public.pgps_id_pgps_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.pgps_id_pgps_seq OWNER TO gps_buffer_user;

--
-- TOC entry 4540 (class 0 OID 0)
-- Dependencies: 202
-- Name: pgps_id_pgps_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: gps_buffer_user
--

ALTER SEQUENCE public.pgps_id_pgps_seq OWNED BY public.pgps.id_pgps;


-- TOC entry 4391 (class 2604 OID 524832)
-- Name: inside idinside; Type: DEFAULT; Schema: public; Owner: gps_buffer_user
--




--
-- TOC entry 4390 (class 2604 OID 524799)
-- Name: pgps id_pgps; Type: DEFAULT; Schema: public; Owner: gps_buffer_user
--

ALTER TABLE ONLY public.pgps ALTER COLUMN id_pgps SET DEFAULT nextval('public.pgps_id_pgps_seq'::regclass);


--
-- TOC entry 4400 (class 2606 OID 524834)
-- Name: inside inside_pkey; Type: CONSTRAINT; Schema: public; Owner: gps_buffer_user
--



--
-- TOC entry 4397 (class 2606 OID 524804)
-- Name: pgps pgps_pkey; Type: CONSTRAINT; Schema: public; Owner: gps_buffer_user
--

ALTER TABLE ONLY public.pgps
    ADD CONSTRAINT pgps_pkey PRIMARY KEY (id_pgps);


--
-- TOC entry 4394 (class 1259 OID 524805)
-- Name: ind_pgps_coordinate; Type: INDEX; Schema: public; Owner: gps_buffer_user
--

CREATE INDEX ind_pgps_coordinate ON public.pgps USING gist (coordinate);


--
-- TOC entry 4395 (class 1259 OID 524806)
-- Name: ind_pgps_time; Type: INDEX; Schema: public; Owner: gps_buffer_user
--

CREATE INDEX ind_pgps_time ON public.pgps USING btree ("time");




--
-- TOC entry 4534 (class 0 OID 0)
-- Dependencies: 190
-- Name: TABLE geography_columns; Type: ACL; Schema: public; Owner: gps_buffer_user
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.geography_columns TO gps_buffer_user;


--
-- TOC entry 4535 (class 0 OID 0)
-- Dependencies: 191
-- Name: TABLE geometry_columns; Type: ACL; Schema: public; Owner: gps_buffer_user
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.geometry_columns TO gps_buffer_user;







--
-- TOC entry 4539 (class 0 OID 0)
-- Dependencies: 203
-- Name: TABLE pgps; Type: ACL; Schema: public; Owner: gps_buffer_user
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.pgps TO gps_buffer_user;


--
-- TOC entry 4541 (class 0 OID 0)
-- Dependencies: 202
-- Name: SEQUENCE pgps_id_pgps_seq; Type: ACL; Schema: public; Owner: gps_buffer_user
--

GRANT SELECT,USAGE ON SEQUENCE public.pgps_id_pgps_seq TO gps_buffer_user;


-- Completed on 2019-09-05 09:49:18

--
-- gps_buffer_userQL database dump complete
--

