"use client";

import type { CSSProperties } from "react";

import { Card } from "./card";
import { Icon } from "./icon";
import { Tag } from "./tag";

export interface SampleCardProps {
  num: string;
  title: string;
  description: string;
  markers: string;
  selected?: boolean;
  cta?: string;
  onClick?: () => void;
  style?: CSSProperties;
}

/** Numbered card for one sample report from `GET /api/samples`. */
export function SampleCard({
  num,
  title,
  description,
  markers,
  selected,
  cta,
  onClick,
  style,
}: SampleCardProps) {
  return (
    <Card onClick={onClick} selected={selected} className="ms-sample" style={style}>
      <span className="ms-sample__top">
        <Tag>{markers}</Tag>
        <Icon name="arrow-up-right" size={40} strokeWidth={1.2} />
      </span>
      <span className="ms-sample__body">
        <span className="ms-sample__title">{title}</span>
        <span className="ms-sample__desc">{description}</span>
      </span>
      <span className="ms-sample__foot">
        {/* Decorative: the number is a position in the list, and the title already names the
            card. Reading "zero one" before every title would be noise. */}
        <span className="ms-sample__num" aria-hidden="true">
          {num}
        </span>
        <span className="ms-sample__cta">{cta ?? (selected ? "Selected" : "Try it")}</span>
      </span>
    </Card>
  );
}
